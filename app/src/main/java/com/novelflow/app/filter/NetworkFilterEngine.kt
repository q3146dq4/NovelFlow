package com.novelflow.app.filter

import java.util.concurrent.ConcurrentHashMap

private const val TYPE_OTHER = 4096

class NetworkFilterEngine private constructor(
  private val hostAnchoredRules: Map<String, List<CompiledNetworkRule>>,
  private val tokenIndexedRules: Map<String, List<CompiledNetworkRule>>,
  private val fallbackRules: List<CompiledNetworkRule>,
) {
  private val resultCache = ConcurrentHashMap<NetworkRequestKey, Boolean>()

  fun shouldBlock(request: FilterRequest): Boolean {
    val key = NetworkRequestKey(request.url, request.sourceUrl, request.requestType)
    return resultCache.computeIfAbsent(key) { evaluate(request) }
  }

  private fun evaluate(request: FilterRequest): Boolean {
    val candidates = LinkedHashSet<CompiledNetworkRule>()
    addHostCandidates(candidates, request.host)
    collectLookupTokens(request).forEach { token ->
      tokenIndexedRules[token]?.let(candidates::addAll)
    }
    candidates.addAll(fallbackRules)

    var blocked = false
    candidates.forEach { rule ->
      if (!rule.matches(request)) return@forEach
      if (rule.isException) return false
      blocked = true
    }
    return blocked
  }

  private fun addHostCandidates(
    target: LinkedHashSet<CompiledNetworkRule>,
    host: String,
  ) {
    if (host.isBlank()) return
    var offset = 0
    while (offset < host.length) {
      val suffix = host.substring(offset)
      hostAnchoredRules[suffix]?.let(target::addAll)
      val nextDot = host.indexOf('.', offset)
      if (nextDot == -1) break
      offset = nextDot + 1
    }
  }

  private fun collectLookupTokens(request: FilterRequest): Set<String> {
    val tokens = LinkedHashSet<String>()
    addLookupTokens(tokens, request.url)
    if (request.sourceUrl != null) addLookupTokens(tokens, request.sourceUrl)
    if (request.host.isNotBlank()) addHostTokens(tokens, request.host)
    if (!request.sourceHost.isNullOrBlank()) addHostTokens(tokens, request.sourceHost)
    return tokens
  }

  private fun addLookupTokens(
    target: MutableSet<String>,
    value: String,
  ) {
    TOKEN_REGEX.findAll(value.lowercase()).forEach { match ->
      val token = match.value
      if (token.length >= 3) target += token
    }
  }

  private fun addHostTokens(
    target: MutableSet<String>,
    host: String,
  ) {
    host.split('.').filter { it.length >= 3 }.forEach(target::add)
  }

  companion object {
    private val TOKEN_REGEX = Regex("[a-z0-9%]{3,}")

    fun empty(): NetworkFilterEngine = NetworkFilterEngine(emptyMap(), emptyMap(), emptyList())

    fun create(rules: List<CompiledNetworkRule>): NetworkFilterEngine {
      if (rules.isEmpty()) return empty()

      val hostRules = LinkedHashMap<String, MutableList<CompiledNetworkRule>>()
      val tokenRules = LinkedHashMap<String, MutableList<CompiledNetworkRule>>()
      val fallbackRules = mutableListOf<CompiledNetworkRule>()

      rules.forEach { rule ->
        when {
          !rule.hostAnchor.isNullOrBlank() -> hostRules.getOrPut(rule.hostAnchor) { mutableListOf() } += rule
          !rule.lookupToken.isNullOrBlank() -> tokenRules.getOrPut(rule.lookupToken) { mutableListOf() } += rule
          else -> fallbackRules += rule
        }
      }

      return NetworkFilterEngine(
        hostAnchoredRules = hostRules.mapValues { it.value.toList() },
        tokenIndexedRules = tokenRules.mapValues { it.value.toList() },
        fallbackRules = fallbackRules.toList(),
      )
    }
  }
}

private data class NetworkRequestKey(
  val url: String,
  val sourceUrl: String?,
  val requestType: Int,
)

data class CompiledNetworkRule(
  val isException: Boolean,
  val matcher: Regex,
  val hostAnchor: String?,
  val lookupToken: String?,
  val includedRequestTypes: Set<Int>,
  val excludedRequestTypes: Set<Int>,
  val scope: DomainRuleScope,
) {
  fun matches(request: FilterRequest): Boolean {
    if (includedRequestTypes.isNotEmpty() && request.requestType !in includedRequestTypes) return false
    if (request.requestType in excludedRequestTypes) return false
    val scopeHost = request.sourceHost?.takeIf { it.isNotBlank() } ?: request.host
    if (!scope.matches(scopeHost)) return false
    return matcher.containsMatchIn(request.url)
  }
}

object NetworkRuleParser {
  private val OPTION_SEPARATOR = Regex(",(?=(?:[^|]*\\|[^|]*|[^|]*)*$)")
  private val META_CHARS = setOf('*', '^', '|')
  private val REGEX_SPECIALS = setOf('.', '+', '?', '(', ')', '[', ']', '{', '}', '\\')
  private val SUPPORTED_TYPES =
    mapOf(
      "document" to 1,
      "subdocument" to 2,
      "script" to 4,
      "stylesheet" to 8,
      "object" to 16,
      "image" to 32,
      "xmlhttprequest" to 64,
      "media" to 128,
      "font" to 256,
      "other" to TYPE_OTHER,
    )

  fun parse(line: String): CompiledNetworkRule? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) return null
    if ("##" in trimmed || "#@#" in trimmed) return null

    val isException = trimmed.startsWith("@@")
    val body = if (isException) trimmed.removePrefix("@@") else trimmed
    if (body.isBlank() || body.startsWith("/") && body.endsWith("/") && body.length > 1) return null

    val patternAndOptions = splitPatternAndOptions(body) ?: return null
    val requestTypes = parseRequestTypes(patternAndOptions.options) ?: return null
    val scope = parseDomainScope(patternAndOptions.options) ?: return null
    val regex = buildRegex(patternAndOptions.pattern) ?: return null

    return CompiledNetworkRule(
      isException = isException,
      matcher = regex,
      hostAnchor = extractHostAnchor(patternAndOptions.pattern),
      lookupToken = extractLookupToken(patternAndOptions.pattern),
      includedRequestTypes = requestTypes.first,
      excludedRequestTypes = requestTypes.second,
      scope = scope,
    )
  }

  private fun splitPatternAndOptions(body: String): PatternAndOptions? {
    val index = body.indexOf('$')
    if (index == -1) return PatternAndOptions(body, emptyList())
    val pattern = body.substring(0, index)
    val optionText = body.substring(index + 1)
    if (pattern.isBlank()) return null
    val options = optionText.split(OPTION_SEPARATOR).map { it.trim() }.filter { it.isNotBlank() }
    return PatternAndOptions(pattern, options)
  }

  private fun parseRequestTypes(options: List<String>): Pair<Set<Int>, Set<Int>>? {
    val included = linkedSetOf<Int>()
    val excluded = linkedSetOf<Int>()

    for (option in options) {
      if (option.startsWith("domain=")) continue
      val isNegated = option.startsWith("~")
      val normalized = option.removePrefix("~").lowercase()
      val requestType = SUPPORTED_TYPES[normalized] ?: return null
      if (isNegated) {
        excluded += requestType
      } else {
        included += requestType
      }
    }

    return included to excluded
  }

  private fun parseDomainScope(options: List<String>): DomainRuleScope? {
    val domainOption = options.firstOrNull { it.startsWith("domain=", ignoreCase = true) } ?: return DomainRuleScope()
    val body = domainOption.substringAfter('=')
    if (body.isBlank()) return null

    val included = linkedSetOf<String>()
    val excluded = linkedSetOf<String>()
    body.split('|').map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { token ->
      if (token.startsWith("~")) {
        excluded += token.removePrefix("~")
      } else {
        included += token
      }
    }
    return DomainRuleScope(includedDomains = included, excludedDomains = excluded)
  }

  private fun buildRegex(pattern: String): Regex? {
    var body = pattern
    val domainAnchored = body.startsWith("||")
    val startAnchored = !domainAnchored && body.startsWith("|")
    val endAnchored = body.endsWith("|")

    if (domainAnchored) {
      body = body.removePrefix("||")
    } else if (startAnchored) {
      body = body.removePrefix("|")
    }
    if (endAnchored) body = body.dropLast(1)
    if (body.isBlank()) return null

    val regex = StringBuilder()
    if (domainAnchored) {
      regex.append("^[a-z][a-z0-9+.-]*://(?:[^/]*\\.)?")
    } else if (startAnchored) {
      regex.append("^")
    }

    body.forEach { ch ->
      when (ch) {
        '*' -> regex.append(".*")
        '^' -> regex.append("(?:[^A-Za-z0-9._%\\-]|$)")
        else -> {
          if (ch in REGEX_SPECIALS) regex.append('\\')
          regex.append(ch)
        }
      }
    }

    if (endAnchored) regex.append('$')
    return runCatching { Regex(regex.toString(), RegexOption.IGNORE_CASE) }.getOrNull()
  }

  private fun extractHostAnchor(pattern: String): String? {
    if (!pattern.startsWith("||")) return null
    val host =
      buildString {
        pattern.removePrefix("||").forEach { ch ->
          if (ch == '/' || ch in META_CHARS) return@buildString
          append(ch.lowercaseChar())
        }
      }
    return host.ifBlank { null }
  }

  private fun extractLookupToken(pattern: String): String? {
    val normalized =
      pattern
        .removePrefix("||")
        .removePrefix("|")
        .removeSuffix("|")
        .lowercase()
    val candidates = normalized.split('*', '^', '/', '.', '?', '&', '=', '-', '_', ':').filter { it.length >= 3 }
    return candidates.maxByOrNull { it.length }
  }

  private data class PatternAndOptions(
    val pattern: String,
    val options: List<String>,
  )
}
