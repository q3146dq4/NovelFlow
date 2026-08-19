package com.NovelRegEx.app.setting

import android.content.Context
import androidx.preference.PreferenceManager
import com.NovelRegEx.app.bookmark.BookmarkItem
import com.NovelRegEx.app.bookmark.BookmarkRepository
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.tts.TtsPreferences
import com.NovelRegEx.app.tts.TtsPronunciationDictionary
import com.NovelRegEx.app.tts.TtsRegexRule
import com.NovelRegEx.app.tts.TtsRegexStore
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object SettingBackupCodec {
  const val START_PAGE_HOME = "home"
  const val START_PAGE_MYBOOK = "mybook"
  const val START_PAGE_LAST_VIEW = "last_view"

  private const val SCHEMA_VERSION = 3
  private const val START_PAGE_HOME_URL = "https://novelpia.com"
  private const val START_PAGE_LAST_VIEW_URL = "https://novelpia.com/mybook/last_view"
  private const val START_PAGE_MYBOOK_URL = "https://novelpia.com/mybook"

  private val volumeBehaviors = setOf("move_page", "disable")
  private val volumeDirections = setOf("up_prev", "up_next")
  private val swipeFractions =
    setOf(
      "0.05",
      "0.10",
      "0.15",
      "0.20",
      "0.25",
      "0.30",
      "0.35",
      "0.40",
      "0.45",
      "0.50",
    )

  fun export(
    context: Context,
    appVersion: String,
    exportedAt: String,
    bookmarks: List<BookmarkItem>,
  ): String {
    val settings = readSettings(context)
    return JSONObject()
      .put("schemaVersion", SCHEMA_VERSION)
      .put("exportedAt", exportedAt)
      .put("appVersion", appVersion)
      .put("settings", settings.toJson())
      .put("bookmarks", bookmarks.toJson())
      .toString()
  }

  fun parse(rawJson: String): SettingBackup {
    val root = JSONObject(rawJson)
    val schemaVersion = root.getInt("schemaVersion")
    require(schemaVersion in 1..SCHEMA_VERSION)
    return SettingBackup(
      settings = parseSettings(root.getJSONObject("settings"), schemaVersion),
      bookmarks = parseBookmarks(root.getJSONArray("bookmarks")),
      exportedAt = root.optionalString("exportedAt"),
      appVersion = root.optionalString("appVersion"),
    )
  }

  fun startPageKeyToUrl(key: String): String =
    when (key) {
      START_PAGE_HOME -> START_PAGE_HOME_URL
      START_PAGE_MYBOOK -> START_PAGE_MYBOOK_URL
      START_PAGE_LAST_VIEW -> START_PAGE_LAST_VIEW_URL
      else -> error("Unsupported start page key")
    }

  fun startPageUrlToKey(url: String): String =
    when (url) {
      START_PAGE_HOME_URL -> START_PAGE_HOME
      START_PAGE_MYBOOK_URL -> START_PAGE_MYBOOK
      START_PAGE_LAST_VIEW_URL -> START_PAGE_LAST_VIEW
      else -> START_PAGE_MYBOOK
    }

  private fun readSettings(context: Context): BackupSettings {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    return BackupSettings(
      startPage = startPageUrlToKey(prefs.getString("start_page", START_PAGE_MYBOOK_URL) ?: START_PAGE_MYBOOK_URL),
      volumeBehavior = validOrDefault(prefs.getString("volume_behavior", "move_page"), volumeBehaviors, "move_page"),
      volumeDirection = validOrDefault(prefs.getString("volume_direction", "up_prev"), volumeDirections, "up_prev"),
      swipeFraction = validOrDefault(prefs.getString("swipe_fraction", "0.15"), swipeFractions, "0.15"),
      ttsChunkMode = TtsPreferences.getChunkMode(context),
      ttsRollingPrequeueDepth = TtsPreferences.getRollingPrequeueDepth(context),
      filtersEnabled = prefs.getBoolean(FilterPreferences.KEY_ENABLED, true),
      filtersAutoUpdate = prefs.getBoolean(FilterPreferences.KEY_AUTO_UPDATE, true),
      filterSubscriptions = FilterPreferences.getSubscriptionUrls(context),
      filterUserRules = FilterPreferences.getUserRuleLines(context),
      filterDisabledUserRules = FilterPreferences.getDisabledUserRuleLines(context),
      autoCheckUpdate = prefs.getBoolean("auto_check_update", true),
      ttsRegexRules = TtsRegexStore.load(context),
      ttsNovelRegexRulesJson = TtsRegexStore.exportNovelRulesJson(context).toString(),
      ttsKoreanNumberEnabled = TtsRegexStore.isKoreanNumberEnabled(context),
      ttsEnginePackage = prefs.getString("tts_engine_package", "")?.trim()?.takeIf { it.isNotEmpty() },
      ttsSpeechRate = TtsPreferences.getSpeechRate(context),
      ttsSleepMinutes = TtsPreferences.getSleepMinutes(context),
      ttsStopEpisodes = TtsPreferences.exportStopEpisodes(context),
      ttsPronunciationDictionaryJson = TtsPronunciationDictionary.exportJson(context).toString(),
    )
  }

  private fun parseSettings(
    json: JSONObject,
    schemaVersion: Int,
  ): BackupSettings =
    BackupSettings(
      startPage = json.requireString("startPage", setOf(START_PAGE_HOME, START_PAGE_MYBOOK, START_PAGE_LAST_VIEW)),
      volumeBehavior = json.requireString("volumeBehavior", volumeBehaviors),
      volumeDirection = json.requireString("volumeDirection", volumeDirections),
      swipeFraction = json.requireString("swipeFraction", swipeFractions),
      ttsChunkMode =
        json
          .optString("ttsChunkMode", TtsPreferences.DEFAULT_CHUNK_MODE)
          .takeIf { it in TtsPreferences.chunkModes }
          ?: TtsPreferences.DEFAULT_CHUNK_MODE,
      ttsRollingPrequeueDepth =
        json
          .optInt("ttsRollingPrequeueDepth", TtsPreferences.DEFAULT_ROLLING_PREQUEUE_DEPTH)
          .takeIf { it in TtsPreferences.rollingPrequeueDepths }
          ?: TtsPreferences.DEFAULT_ROLLING_PREQUEUE_DEPTH,
      filtersEnabled = json.getBoolean("filtersEnabled"),
      filtersAutoUpdate = json.getBoolean("filtersAutoUpdate"),
      filterSubscriptions = parseStringArray(json.getJSONArray("filterSubscriptions")),
      filterUserRules = parseStringArray(json.getJSONArray("filterUserRules")),
      filterDisabledUserRules =
        json.optJSONArray("filterDisabledUserRules")?.let(::parseStringArray).orEmpty(),
      autoCheckUpdate = json.getBoolean("autoCheckUpdate"),
      ttsRegexRules =
        if (schemaVersion >= 2) {
          buildList {
            val array = json.optJSONArray("ttsRegexRules") ?: JSONArray()
            for (index in 0 until array.length()) add(TtsRegexRule.fromJson(array.getJSONObject(index)))
          }
        } else null,
      ttsNovelRegexRulesJson =
        if (schemaVersion >= 3) json.optJSONObject("ttsNovelRegexRules")?.toString() else null,
      ttsKoreanNumberEnabled =
        if (schemaVersion >= 2) json.optBoolean("ttsKoreanNumberEnabled", true) else null,
      ttsEnginePackage =
        if (schemaVersion >= 2) {
          if (json.has("ttsEnginePackage") && !json.isNull("ttsEnginePackage")) {
            json.optString("ttsEnginePackage", "").trim()
          } else {
            ""
          }
        } else null,
      ttsSpeechRate =
        if (schemaVersion >= 2) {
          json.optDouble("ttsSpeechRate", TtsPreferences.DEFAULT_SPEECH_RATE.toDouble())
            .toFloat()
            .coerceIn(TtsPreferences.MIN_SPEECH_RATE, TtsPreferences.MAX_SPEECH_RATE)
        } else null,
      ttsSleepMinutes =
        if (schemaVersion >= 2) {
          json.optInt("ttsSleepMinutes", TtsPreferences.DEFAULT_SLEEP_MINUTES).coerceIn(1, 1440)
        } else null,
      ttsStopEpisodes =
        if (schemaVersion >= 2) {
          buildMap {
            val objectValue = json.optJSONObject("ttsStopEpisodes") ?: JSONObject()
            val keys = objectValue.keys()
            while (keys.hasNext()) {
              val novelNo = keys.next()
              val episode = objectValue.optInt(novelNo, 0)
              if (novelNo.matches(Regex("""\d+""")) && episode > 0) put(novelNo, episode)
            }
          }
        } else null,
      ttsPronunciationDictionaryJson =
        if (schemaVersion >= 2) json.optJSONObject("ttsPronunciationDictionary")?.toString() else null,
    )

  private fun parseBookmarks(array: JSONArray): List<BookmarkItem> =
    buildList {
      for (index in 0 until array.length()) {
        val item = array.getJSONObject(index)
        val title = item.getString("title").trim()
        val url = BookmarkRepository.normalizeUrl(item.getString("url")) ?: error("Invalid bookmark URL")
        require(title.isNotBlank())
        add(BookmarkItem(UUID.randomUUID().toString(), title, url))
      }
    }

  private fun BackupSettings.toJson(): JSONObject =
    JSONObject()
      .put("startPage", startPage)
      .put("volumeBehavior", volumeBehavior)
      .put("volumeDirection", volumeDirection)
      .put("swipeFraction", swipeFraction)
      .put("ttsChunkMode", ttsChunkMode)
      .put("ttsRollingPrequeueDepth", ttsRollingPrequeueDepth)
      .put("filtersEnabled", filtersEnabled)
      .put("filtersAutoUpdate", filtersAutoUpdate)
      .put("filterSubscriptions", filterSubscriptions.toJsonArray())
      .put("filterUserRules", filterUserRules.toJsonArray())
      .put("filterDisabledUserRules", filterDisabledUserRules.toJsonArray())
      .put("autoCheckUpdate", autoCheckUpdate)
      .put("ttsRegexRules", JSONArray().apply { ttsRegexRules.orEmpty().forEach { put(it.toJson()) } })
      .put("ttsNovelRegexRules", ttsNovelRegexRulesJson?.let(::JSONObject) ?: JSONObject())
      .put("ttsKoreanNumberEnabled", ttsKoreanNumberEnabled ?: true)
      .put("ttsEnginePackage", ttsEnginePackage ?: JSONObject.NULL)
      .put("ttsSpeechRate", ttsSpeechRate ?: TtsPreferences.DEFAULT_SPEECH_RATE)
      .put("ttsSleepMinutes", ttsSleepMinutes ?: TtsPreferences.DEFAULT_SLEEP_MINUTES)
      .put(
        "ttsStopEpisodes",
        JSONObject().apply { ttsStopEpisodes.orEmpty().forEach { (novelNo, episode) -> put(novelNo, episode) } },
      )
      .put("ttsPronunciationDictionary", ttsPronunciationDictionaryJson?.let(::JSONObject) ?: JSONObject())

  private fun List<BookmarkItem>.toJson(): JSONArray {
    val array = JSONArray()
    forEach { item ->
      array.put(
        JSONObject()
          .put("title", item.title)
          .put("url", item.url),
      )
    }
    return array
  }

  private fun parseStringArray(array: JSONArray): List<String> =
    buildList {
      for (index in 0 until array.length()) {
        val value = array.get(index)
        require(value is String)
        val item = value.trim()
        require(item.isNotEmpty())
        add(item)
      }
    }.distinct()

  private fun List<String>.toJsonArray(): JSONArray {
    val array = JSONArray()
    forEach { url -> array.put(url) }
    return array
  }

  private fun JSONObject.requireString(
    key: String,
    allowedValues: Set<String>,
  ): String {
    val value = getString(key)
    require(value in allowedValues)
    return value
  }

  private fun JSONObject.optionalString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

  private fun validOrDefault(
    value: String?,
    allowedValues: Set<String>,
    defaultValue: String,
  ): String = if (value in allowedValues) value.orEmpty() else defaultValue
}

data class SettingBackup(
  val settings: BackupSettings,
  val bookmarks: List<BookmarkItem>,
  val exportedAt: String?,
  val appVersion: String?,
)

data class BackupSettings(
  val startPage: String,
  val volumeBehavior: String,
  val volumeDirection: String,
  val swipeFraction: String,
  val ttsChunkMode: String,
  val ttsRollingPrequeueDepth: Int,
  val filtersEnabled: Boolean,
  val filtersAutoUpdate: Boolean,
  val filterSubscriptions: List<String>,
  val filterUserRules: List<String>,
  val filterDisabledUserRules: List<String>,
  val autoCheckUpdate: Boolean,
  val ttsRegexRules: List<TtsRegexRule>? = null,
  val ttsNovelRegexRulesJson: String? = null,
  val ttsKoreanNumberEnabled: Boolean? = null,
  val ttsEnginePackage: String? = null,
  val ttsSpeechRate: Float? = null,
  val ttsSleepMinutes: Int? = null,
  val ttsStopEpisodes: Map<String, Int>? = null,
  val ttsPronunciationDictionaryJson: String? = null,
)
