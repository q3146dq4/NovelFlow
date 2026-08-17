package com.NovelRegEx.app.setting

import android.content.Context
import androidx.preference.PreferenceManager
import com.NovelRegEx.app.bookmark.BookmarkItem
import com.NovelRegEx.app.bookmark.BookmarkRepository
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.tts.TtsPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object SettingBackupCodec {
  const val START_PAGE_HOME = "home"
  const val START_PAGE_MYBOOK = "mybook"
  const val START_PAGE_LAST_VIEW = "last_view"

  private const val SCHEMA_VERSION = 1
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
    require(root.getInt("schemaVersion") == SCHEMA_VERSION)
    return SettingBackup(
      settings = parseSettings(root.getJSONObject("settings")),
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
      filtersEnabled = prefs.getBoolean(FilterPreferences.KEY_ENABLED, true),
      filtersAutoUpdate = prefs.getBoolean(FilterPreferences.KEY_AUTO_UPDATE, true),
      filterSubscriptions = FilterPreferences.getSubscriptionUrls(context),
      filterUserRules = FilterPreferences.getUserRuleLines(context),
      autoCheckUpdate = prefs.getBoolean("auto_check_update", true),
    )
  }

  private fun parseSettings(json: JSONObject): BackupSettings =
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
      filtersEnabled = json.getBoolean("filtersEnabled"),
      filtersAutoUpdate = json.getBoolean("filtersAutoUpdate"),
      filterSubscriptions = parseStringArray(json.getJSONArray("filterSubscriptions")),
      filterUserRules = parseStringArray(json.getJSONArray("filterUserRules")),
      autoCheckUpdate = json.getBoolean("autoCheckUpdate"),
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
      .put("filtersEnabled", filtersEnabled)
      .put("filtersAutoUpdate", filtersAutoUpdate)
      .put("filterSubscriptions", filterSubscriptions.toJsonArray())
      .put("filterUserRules", filterUserRules.toJsonArray())
      .put("autoCheckUpdate", autoCheckUpdate)

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
  val filtersEnabled: Boolean,
  val filtersAutoUpdate: Boolean,
  val filterSubscriptions: List<String>,
  val filterUserRules: List<String>,
  val autoCheckUpdate: Boolean,
)
