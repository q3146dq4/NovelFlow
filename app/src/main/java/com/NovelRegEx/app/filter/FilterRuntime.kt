package com.NovelRegEx.app.filter

import android.app.Application
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import java.io.ByteArrayInputStream

class FilterRuntime private constructor(
  private val app: Application,
) {
  companion object {
    @Volatile
    private var instance: FilterRuntime? = null

    fun getInstance(context: Context): FilterRuntime =
      instance ?: synchronized(this) {
        instance ?: FilterRuntime(context.applicationContext as Application).also { instance = it }
      }
  }

  private val repository = FilterRepository(app)
  private val cosmeticCache = SynchronizedLruCache<String, FilterCosmeticPayload>(128)
  private val compileLock = Any()

  @Volatile
  private var currentFingerprint = ""

  @Volatile
  private var compiledFilters =
    CompiledFilterSet(
      fingerprint = "",
      networkEngine = NetworkFilterEngine.empty(),
      cosmeticEngine = CosmeticFilterEngine.empty(),
    )

  private val imageExtRegex = Regex(".*\\.(png|jpe?g|gif|webp|svg)(\\?.*)?$", RegexOption.IGNORE_CASE)
  private val fontExtRegex = Regex(".*\\.(woff2?|ttf|otf)(\\?.*)?$", RegexOption.IGNORE_CASE)
  private val scriptExtRegex = Regex(".*\\.(js|mjs)(\\?.*)?$", RegexOption.IGNORE_CASE)

  @Synchronized
  fun updateSubscriptions(force: Boolean = false): FilterUpdateSummary = repository.updateSubscriptions(force)

  fun refreshEngine(force: Boolean = false) {
    synchronized(compileLock) {
      if (!FilterPreferences.isEnabled(app)) {
        currentFingerprint = ""
        compiledFilters =
          CompiledFilterSet(
            fingerprint = "",
            networkEngine = NetworkFilterEngine.empty(),
            cosmeticEngine = CosmeticFilterEngine.empty(),
          )
        cosmeticCache.clear()
        repository.invalidateRuleCache()
        return
      }

      val snapshot = repository.loadRuleSnapshot(forceReload = force)
      if (!force && snapshot.fingerprint == currentFingerprint) return

      compiledFilters = FilterCompiler.compile(snapshot)
      currentFingerprint = snapshot.fingerprint
      cosmeticCache.clear()
    }
  }

  fun getCosmeticForUrl(url: String): FilterCosmeticPayload = cosmeticCache[normalizeUrl(url)] ?: FilterCosmeticPayload("", emptyList())

  fun preparePage(url: String) {
    val normalizedUrl = normalizeUrl(url)
    if (!FilterPreferences.isEnabled(app) || !repository.hasAnyActiveSource()) {
      cosmeticCache.remove(normalizedUrl)
      return
    }

    ensureEngine()
    val engine = compiledFilters.cosmeticEngine
    cosmeticCache.put(normalizedUrl, engine.createPayload(normalizedUrl))
  }

  fun maybeBlock(request: WebResourceRequest): WebResourceResponse? {
    if (!FilterPreferences.isEnabled(app) || !repository.hasAnyActiveSource()) return null

    ensureEngine()
    val filterRequest =
      FilterRequest(
        url = request.url.toString(),
        sourceUrl = request.requestHeaders["Referer"],
        requestType = resolveRequestType(request),
        host = extractHost(request.url.toString()),
        sourceHost = extractHost(request.requestHeaders["Referer"]),
      )

    return if (compiledFilters.networkEngine.shouldBlock(filterRequest)) emptyResponse() else null
  }

  private fun ensureEngine() {
    if (compiledFilters.fingerprint.isNotEmpty()) return
    refreshEngine()
  }

  private fun resolveRequestType(request: WebResourceRequest): Int {
    if (request.isForMainFrame) return 1
    val destination = request.requestHeaders["Sec-Fetch-Dest"].orEmpty()
    return when {
      destination == "iframe" -> 2
      destination == "script" -> 4
      destination == "style" -> 8
      destination == "image" -> 32
      destination == "font" -> 256
      destination == "video" || destination == "audio" -> 128
      destination == "object" || destination == "embed" -> 16
      destination == "empty" -> 64
      request.url.toString().endsWith(".css", ignoreCase = true) -> 8
      request.url.toString().matches(imageExtRegex) -> 32
      request.url.toString().matches(fontExtRegex) -> 256
      request.url.toString().matches(scriptExtRegex) -> 4
      else -> 4096
    }
  }

  private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse(
      "text/plain",
      "utf-8",
      204,
      "No Content",
      emptyMap(),
      ByteArrayInputStream(ByteArray(0)),
    )

  private fun normalizeUrl(url: String): String =
    runCatching {
      url
        .toUri()
        .buildUpon()
        .fragment(null)
        .build()
        .toString()
    }.getOrDefault(url)
}
