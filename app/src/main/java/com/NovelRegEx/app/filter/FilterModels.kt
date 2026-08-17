package com.NovelRegEx.app.filter

import androidx.core.net.toUri

data class FilterCosmeticPayload(
  val css: String,
  val selectors: List<String>,
)

data class FilterRequest(
  val url: String,
  val sourceUrl: String?,
  val requestType: Int,
  val host: String,
  val sourceHost: String?,
)

data class DomainRuleScope(
  val includedDomains: Set<String> = emptySet(),
  val excludedDomains: Set<String> = emptySet(),
) {
  fun matches(host: String): Boolean {
    if (host.isBlank()) return includedDomains.isEmpty()
    val included = includedDomains.isEmpty() || includedDomains.any { host.matchesDomain(it) }
    val excluded = excludedDomains.any { host.matchesDomain(it) }
    return included && !excluded
  }
}

internal fun String.matchesDomain(domain: String): Boolean = this == domain || this.endsWith(".$domain")

internal fun extractHost(url: String?): String =
  runCatching {
    url
      ?.toUri()
      ?.host
      .orEmpty()
      .lowercase()
  }.getOrDefault("")
