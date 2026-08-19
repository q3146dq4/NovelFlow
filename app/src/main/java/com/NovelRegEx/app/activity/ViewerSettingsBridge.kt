package com.NovelRegEx.app.activity

import android.content.Intent
import android.webkit.JavascriptInterface
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.filter.FilterRuntime
import com.NovelRegEx.app.tts.TtsPreferences
import com.NovelRegEx.app.tts.TtsRegexStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * JavaScript bridge used only by the NovelRegEx quick-settings panel injected
 * into the Novelpia viewer settings modal.
 */
class ViewerSettingsBridge(
  private val activity: MainActivity,
) {
  companion object {
    private const val START_PAGE_KEY = "start_page"
    private const val START_PAGE_HOME = "https://novelpia.com"
    private const val START_PAGE_LAST_VIEW = "https://novelpia.com/mybook/last_view"
    private const val START_PAGE_MYBOOK = "https://novelpia.com/mybook"

    private val START_PAGES = setOf(START_PAGE_HOME, START_PAGE_LAST_VIEW, START_PAGE_MYBOOK)
    private val VOLUME_BEHAVIORS = setOf("move_page", "disable")
    private val VOLUME_DIRECTIONS = setOf("up_prev", "up_next")
  }

  @JavascriptInterface
  fun getQuickSettings(): String {
    val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
    val allUserRules = FilterPreferences.getUserRuleLines(activity)
    val enabledUserRules = FilterPreferences.getEnabledUserRuleLines(activity)

    return JSONObject()
      .put("chunkMode", TtsPreferences.getChunkMode(activity))
      .put("rollingPreQueueDepth", TtsPreferences.getRollingPrequeueDepth(activity))
      .put("koreanNumberEnabled", TtsRegexStore.isKoreanNumberEnabled(activity))
      .put("ttsRegexCount", TtsRegexStore.load(activity).size)
      .put(
        "startPage",
        prefs
          .getString(START_PAGE_KEY, START_PAGE_MYBOOK)
          ?.takeIf { it in START_PAGES }
          ?: START_PAGE_MYBOOK,
      )
      .put(
        "volumeBehavior",
        prefs
          .getString("volume_behavior", "move_page")
          ?.takeIf { it in VOLUME_BEHAVIORS }
          ?: "move_page",
      )
      .put(
        "volumeDirection",
        prefs
          .getString("volume_direction", "up_prev")
          ?.takeIf { it in VOLUME_DIRECTIONS }
          ?: "up_prev",
      )
      .put("filtersEnabled", FilterPreferences.isEnabled(activity))
      .put("userRuleCount", allUserRules.size)
      .put("enabledUserRuleCount", enabledUserRules.size)
      .toString()
  }

  @JavascriptInterface
  fun setQuickSetting(
    key: String,
    value: String,
  ): Boolean {
    val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    return when (key) {
      TtsPreferences.KEY_CHUNK_MODE -> {
        if (value !in TtsPreferences.chunkModes) return false
        prefs.edit { putString(TtsPreferences.KEY_CHUNK_MODE, value) }
        true
      }

      TtsPreferences.KEY_ROLLING_PREQUEUE_DEPTH -> {
        val depth = value.toIntOrNull() ?: return false
        if (depth !in TtsPreferences.rollingPrequeueDepths) return false
        prefs.edit { putString(TtsPreferences.KEY_ROLLING_PREQUEUE_DEPTH, depth.toString()) }
        true
      }

      "tts_korean_number_enabled" -> {
        val enabled = value.toBooleanStrictOrNull() ?: return false
        TtsRegexStore.setKoreanNumberEnabled(activity, enabled)
        activity.runOnUiThread { activity.refreshTtsPreferencesFromViewerSettings() }
        true
      }

      START_PAGE_KEY -> {
        if (value !in START_PAGES) return false
        prefs.edit { putString(START_PAGE_KEY, value) }
        true
      }

      "volume_behavior" -> {
        if (value !in VOLUME_BEHAVIORS) return false
        prefs.edit { putString("volume_behavior", value) }
        true
      }

      "volume_direction" -> {
        if (value !in VOLUME_DIRECTIONS) return false
        prefs.edit { putString("volume_direction", value) }
        true
      }

      FilterPreferences.KEY_ENABLED -> {
        val enabled = value.toBooleanStrictOrNull() ?: return false
        prefs.edit { putBoolean(FilterPreferences.KEY_ENABLED, enabled) }
        activity.lifecycleScope.launch(Dispatchers.IO) {
          FilterRuntime.getInstance(activity.applicationContext).refreshEngine(force = true)
        }
        true
      }

      else -> false
    }
  }

  @JavascriptInterface
  fun openTtsRegexSettings() {
    activity.runOnUiThread {
      activity.startActivity(Intent(activity, TtsRegexSettingsActivity::class.java))
    }
  }

  @JavascriptInterface
  fun openUserRules() {
    activity.runOnUiThread {
      activity.startActivity(Intent(activity, UserRulesActivity::class.java))
    }
  }

  @JavascriptInterface
  fun openMoreSettings() {
    activity.runOnUiThread {
      activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }
  }
}
