package com.NovelRegEx.app.filter

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class FilterUpdateSummary(
  val updatedCount: Int,
  val failedCount: Int,
  val lastError: String? = null,
)

data class RuleSetSnapshot(
  val fingerprint: String,
  val ruleLines: List<String>,
)

class FilterRepository(
  private val context: Context,
) {
  private val prefs = FilterPreferences.prefs(context)
  private val filtersDir = File(context.filesDir, "filters").apply { mkdirs() }
  private var cachedStateKey: String? = null
  private var cachedSnapshot: RuleSetSnapshot? = null

  fun hasAnyActiveSource(): Boolean =
    FilterPreferences.getSubscriptionUrls(context).isNotEmpty() ||
      FilterPreferences.getUserRuleLines(context).isNotEmpty()

  @Synchronized
  fun loadRuleSnapshot(forceReload: Boolean = false): RuleSetSnapshot {
    val stateKey = buildStateKey()
    val cached = cachedSnapshot
    if (!forceReload && cached != null && cachedStateKey == stateKey) {
      return cached
    }

    val ruleLines = mutableListOf<String>()
    FilterPreferences.getSubscriptionUrls(context).forEach { url ->
      val file = fileForUrl(url)
      if (file.exists()) {
        file.useLines { lines ->
          ruleLines += lines.toList()
        }
      }
    }
    ruleLines += FilterPreferences.getUserRuleLines(context)
    val snapshot =
      RuleSetSnapshot(
        fingerprint = ruleLines.joinToString(separator = "\u0000") { it }.hashCode().toString(),
        ruleLines = ruleLines,
      )
    cachedStateKey = stateKey
    cachedSnapshot = snapshot
    return snapshot
  }

  fun updateSubscriptions(force: Boolean = false): FilterUpdateSummary {
    if (!FilterPreferences.isEnabled(context)) {
      return FilterUpdateSummary(updatedCount = 0, failedCount = 0)
    }
    val urls = FilterPreferences.getSubscriptionUrls(context)
    if (urls.isEmpty()) {
      prefs.edit {
        putLong(FilterPreferences.KEY_LAST_UPDATED_AT, System.currentTimeMillis())
        remove(FilterPreferences.KEY_LAST_UPDATE_ERROR)
      }
      return FilterUpdateSummary(updatedCount = 0, failedCount = 0)
    }
    if (!force && !FilterPreferences.shouldRefresh(context)) {
      return FilterUpdateSummary(updatedCount = 0, failedCount = 0, lastError = FilterPreferences.getLastUpdateError(context))
    }

    var updated = 0
    var failed = 0
    var lastError: String? = null
    urls.forEach { url ->
      runCatching {
        val text = download(url)
        fileForUrl(url).writeText(text)
        updated += 1
      }.onFailure {
        failed += 1
        lastError = it.message ?: url
      }
    }

    prefs.edit {
      putLong(FilterPreferences.KEY_LAST_UPDATED_AT, System.currentTimeMillis())
      if (lastError == null) {
        remove(FilterPreferences.KEY_LAST_UPDATE_ERROR)
      } else {
        putString(FilterPreferences.KEY_LAST_UPDATE_ERROR, lastError)
      }
    }
    invalidateRuleCache()
    return FilterUpdateSummary(updated, failed, lastError)
  }

  @Synchronized
  fun invalidateRuleCache() {
    cachedStateKey = null
    cachedSnapshot = null
  }

  private fun buildStateKey(): String {
    val urls = FilterPreferences.getSubscriptionUrls(context)
    val userRules = FilterPreferences.getUserRuleLines(context)
    val sb = StringBuilder()
    urls.forEach { url ->
      val file = fileForUrl(url)
      sb.append(url)
      sb.append('|')
      if (file.exists()) {
        sb.append(file.length())
        sb.append(':')
        sb.append(file.lastModified())
      } else {
        sb.append("missing")
      }
      sb.append('\n')
    }
    sb.append("user:")
    sb.append(userRules.hashCode())
    return sb.toString()
  }

  private fun download(url: String): String {
    val connection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "NovelRegEx Filter Updater")
      }
    connection.connect()
    if (connection.responseCode !in 200..299) {
      error("HTTP ${connection.responseCode} for $url")
    }
    connection.inputStream.bufferedReader().use { reader ->
      return reader.readText()
    }
  }

  private fun fileForUrl(url: String): File = File(filtersDir, "${sha1(url)}.txt")

  private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
