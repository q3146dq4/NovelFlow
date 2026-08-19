package com.NovelRegEx.app.filter

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
      FilterPreferences.getEnabledUserRuleLines(context).isNotEmpty()

  @Synchronized
  fun loadRuleSnapshot(forceReload: Boolean = false): RuleSetSnapshot {
    val stateKey = buildStateKey()
    val cached = cachedSnapshot
    if (!forceReload && cached != null && cachedStateKey == stateKey) return cached

    val ruleLines = mutableListOf<String>()
    FilterPreferences.getSubscriptionUrls(context).forEach { url ->
      val file = fileForUrl(url)
      if (file.exists()) file.useLines { lines -> ruleLines += lines.toList() }
    }
    ruleLines += FilterPreferences.getEnabledUserRuleLines(context)

    val snapshot =
      RuleSetSnapshot(
        fingerprint = sha256(ruleLines.joinToString("\u0000")),
        ruleLines = ruleLines,
      )
    cachedStateKey = stateKey
    cachedSnapshot = snapshot
    return snapshot
  }

  fun updateSubscriptions(force: Boolean = false): FilterUpdateSummary {
    if (!FilterPreferences.isEnabled(context)) return FilterUpdateSummary(0, 0)

    val urls = FilterPreferences.getSubscriptionUrls(context)
    if (urls.isEmpty()) {
      prefs.edit {
        putLong(FilterPreferences.KEY_LAST_UPDATED_AT, System.currentTimeMillis())
        remove(FilterPreferences.KEY_LAST_UPDATE_ERROR)
      }
      return FilterUpdateSummary(0, 0)
    }
    if (!force && !FilterPreferences.shouldRefresh(context)) {
      return FilterUpdateSummary(0, 0, FilterPreferences.getLastUpdateError(context))
    }

    var updated = 0
    var failed = 0
    var lastError: String? = null
    urls.forEach { url ->
      runCatching {
        atomicWrite(fileForUrl(url), download(url))
        updated++
      }.onFailure {
        failed++
        lastError = it.message ?: url
      }
    }

    prefs.edit {
      putLong(FilterPreferences.KEY_LAST_UPDATED_AT, System.currentTimeMillis())
      if (lastError == null) remove(FilterPreferences.KEY_LAST_UPDATE_ERROR)
      else putString(FilterPreferences.KEY_LAST_UPDATE_ERROR, lastError)
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
    val userRules = FilterPreferences.getEnabledUserRuleLines(context)
    val sb = StringBuilder()
    urls.forEach { url ->
      val file = fileForUrl(url)
      sb.append(url).append('|')
      if (file.exists()) sb.append(file.length()).append(':').append(file.lastModified())
      else sb.append("missing")
      sb.append('\n')
    }
    sb.append("user:").append(sha256(userRules.joinToString("\u0000")))
    return sb.toString()
  }

  private fun atomicWrite(target: File, text: String) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, "${target.name}.tmp")
    try {
      FileOutputStream(temp).use { output ->
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
        output.fd.sync()
      }
      try {
        Files.move(
          temp.toPath(),
          target.toPath(),
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      if (temp.exists()) temp.delete()
    }
  }

  private fun download(url: String): String {
    val connection =
      (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "NovelRegEx Filter Updater")
      }
    return try {
      connection.connect()
      if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode} for $url")
      connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
      connection.disconnect()
    }
  }

  private fun fileForUrl(url: String): File = File(filtersDir, "${sha1(url)}.txt")
  private fun sha1(value: String): String = digest("SHA-1", value)
  private fun sha256(value: String): String = digest("SHA-256", value)

  private fun digest(algorithm: String, value: String): String =
    MessageDigest
      .getInstance(algorithm)
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString("") { "%02x".format(it) }
}
