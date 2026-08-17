package com.novelflow.app.filter

class CosmeticFilterEngine private constructor(
  private val genericRules: List<CompiledCosmeticRule>,
  private val domainRules: List<CompiledCosmeticRule>,
) {
  fun createPayload(url: String): FilterCosmeticPayload {
    val host = extractHost(url)
    if (host.isBlank()) return FilterCosmeticPayload("", emptyList())

    val matches =
      buildList {
        addAll(genericRules)
        addAll(domainRules.filter { it.scope.matches(host) })
      }
    if (matches.isEmpty()) return FilterCosmeticPayload("", emptyList())

    val excludedSelectors =
      matches
        .asSequence()
        .filter { it.isException }
        .map { it.selector }
        .toHashSet()
    val selectors =
      matches
        .asSequence()
        .filterNot { it.isException }
        .map { it.selector }
        .filter { it !in excludedSelectors }
        .distinct()
        .toList()
    if (selectors.isEmpty()) return FilterCosmeticPayload("", emptyList())

    return FilterCosmeticPayload(
      css = selectors.joinToString("\n") { "$it { display: none !important; }" },
      selectors = selectors,
    )
  }

  companion object {
    fun empty(): CosmeticFilterEngine = CosmeticFilterEngine(emptyList(), emptyList())

    fun create(rules: List<CompiledCosmeticRule>): CosmeticFilterEngine {
      if (rules.isEmpty()) return empty()
      return CosmeticFilterEngine(
        genericRules = rules.filter { it.scope.includedDomains.isEmpty() && it.scope.excludedDomains.isEmpty() },
        domainRules = rules.filterNot { it.scope.includedDomains.isEmpty() && it.scope.excludedDomains.isEmpty() },
      )
    }
  }
}

data class CompiledCosmeticRule(
  val selector: String,
  val isException: Boolean,
  val scope: DomainRuleScope,
)

object CosmeticRuleParser {
  fun parse(line: String): CompiledCosmeticRule? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("[")) return null

    val separator =
      when {
        "#@#" in trimmed -> "#@#"
        "##" in trimmed -> "##"
        else -> return null
      }
    val parts = trimmed.split(separator, limit = 2)
    if (parts.size != 2) return null

    val selector = parts[1].trim()
    if (selector.isBlank() || selector.contains("##")) return null

    val included = linkedSetOf<String>()
    val excluded = linkedSetOf<String>()
    parts[0].split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.forEach { domain ->
      if (domain.startsWith("~")) {
        excluded += domain.removePrefix("~")
      } else {
        included += domain
      }
    }

    return CompiledCosmeticRule(
      selector = selector,
      isException = separator == "#@#",
      scope = DomainRuleScope(includedDomains = included, excludedDomains = excluded),
    )
  }
}
