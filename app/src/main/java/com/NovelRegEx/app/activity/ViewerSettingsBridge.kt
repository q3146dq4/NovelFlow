package com.NovelRegEx.app.activity

import android.content.Intent
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.filter.FilterRuntime
import com.NovelRegEx.app.tts.TtsPreferences
import com.NovelRegEx.app.tts.TtsRegexStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
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
    private const val TTS_ENGINE_PACKAGE_KEY = "tts_engine_package"
    private const val START_PAGE_HOME = "https://novelpia.com"
    private const val START_PAGE_LAST_VIEW = "https://novelpia.com/mybook/last_view"
    private const val START_PAGE_MYBOOK = "https://novelpia.com/mybook"

    private val START_PAGES = setOf(START_PAGE_HOME, START_PAGE_LAST_VIEW, START_PAGE_MYBOOK)
    private val VOLUME_BEHAVIORS = setOf("move_page", "disable")
    private val VOLUME_DIRECTIONS = setOf("up_prev", "up_next")
  }

  private fun systemScaledCssPx(sp: Float): Float {
    val metrics = activity.resources.displayMetrics
    val physicalPx =
      TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp,
        metrics,
      )
    val density = metrics.density.takeIf { it > 0f } ?: 1f
    return physicalPx / density
  }

  private fun installedTtsEngines(): List<Pair<String, String>> {
    val pm = activity.packageManager
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    return pm
      .queryIntentServices(intent, 0)
      .mapNotNull { resolveInfo ->
        val packageName = resolveInfo.serviceInfo?.packageName?.trim().orEmpty()
        if (packageName.isBlank()) return@mapNotNull null
        val label =
          runCatching { resolveInfo.loadLabel(pm)?.toString()?.trim() }
            .getOrNull()
            .orEmpty()
            .ifBlank { packageName }
        packageName to label
      }
      .distinctBy { it.first }
      .sortedBy { it.second.lowercase() }
  }

  @Suppress("DEPRECATION")
  private fun systemTtsEnginePackage(): String =
    Settings.Secure
      .getString(activity.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
      ?.trim()
      .orEmpty()

  private fun buildTtsEngineChoices(
    selectedPackage: String,
    installed: List<Pair<String, String>>,
  ): JSONArray {
    val result = JSONArray()
    val systemPackage = systemTtsEnginePackage()
    val systemLabel = installed.firstOrNull { it.first == systemPackage }?.second
    result.put(
      JSONObject()
        .put("value", "")
        .put(
          "label",
          if (systemLabel.isNullOrBlank()) "시스템 기본 엔진" else "시스템 기본 · $systemLabel",
        ),
    )
    installed.forEach { (packageName, label) ->
      result.put(JSONObject().put("value", packageName).put("label", label))
    }
    if (selectedPackage.isNotBlank() && installed.none { it.first == selectedPackage }) {
      result.put(
        JSONObject()
          .put("value", selectedPackage)
          .put("label", "$selectedPackage (설치되지 않음)"),
      )
    }
    return result
  }

  @JavascriptInterface
  fun getQuickSettings(): String {
    val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
    val allUserRules = FilterPreferences.getUserRuleLines(activity)
    val enabledUserRules = FilterPreferences.getEnabledUserRuleLines(activity)
    val installedEngines = installedTtsEngines()
    val selectedTtsEngine = prefs.getString(TTS_ENGINE_PACKAGE_KEY, "").orEmpty()

    return JSONObject()
      .put("selectFontSizeCssPx", systemScaledCssPx(14f).toDouble())
      .put("ttsEnginePackage", selectedTtsEngine)
      .put("ttsEngines", buildTtsEngineChoices(selectedTtsEngine, installedEngines))
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
      TTS_ENGINE_PACKAGE_KEY -> {
        val allowedPackages = installedTtsEngines().map { it.first }.toSet()
        if (value.isNotBlank() && value !in allowedPackages) return false
        prefs.edit {
          if (value.isBlank()) remove(TTS_ENGINE_PACKAGE_KEY)
          else putString(TTS_ENGINE_PACKAGE_KEY, value)
        }
        activity.runOnUiThread { activity.refreshTtsEngineFromViewerSettings() }
        true
      }

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

  private data class QuickChoice(
    val value: String,
    val label: String,
  )

  private data class QuickChoiceSpec(
    val title: String,
    val selectedValue: String,
    val choices: List<QuickChoice>,
  )

  private fun quickChoiceSpec(key: String): QuickChoiceSpec? {
    val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    return when (key) {
      TTS_ENGINE_PACKAGE_KEY -> {
        val installed = installedTtsEngines()
        val systemPackage = systemTtsEnginePackage()
        val systemLabel = installed.firstOrNull { it.first == systemPackage }?.second
        val choices =
          buildList {
            add(
              QuickChoice(
                "",
                if (systemLabel.isNullOrBlank()) {
                  "시스템 기본 엔진"
                } else {
                  "시스템 기본 · $systemLabel"
                },
              ),
            )
            installed.forEach { (packageName, label) ->
              add(QuickChoice(packageName, label))
            }
          }

        QuickChoiceSpec(
          title = "TTS 엔진",
          selectedValue = prefs.getString(TTS_ENGINE_PACKAGE_KEY, "").orEmpty(),
          choices = choices,
        )
      }

      TtsPreferences.KEY_CHUNK_MODE ->
        QuickChoiceSpec(
          title = "청크 단위",
          selectedValue = TtsPreferences.getChunkMode(activity),
          choices =
            listOf(
              QuickChoice(TtsPreferences.CHUNK_MODE_COMMA, "쉼표"),
              QuickChoice(TtsPreferences.CHUNK_MODE_SENTENCE, "마침표"),
              QuickChoice(TtsPreferences.CHUNK_MODE_PARAGRAPH, "문단"),
            ),
        )

      TtsPreferences.KEY_ROLLING_PREQUEUE_DEPTH ->
        QuickChoiceSpec(
          title = "Rolling Pre-Queue",
          selectedValue = TtsPreferences.getRollingPrequeueDepth(activity).toString(),
          choices =
            listOf(
              QuickChoice("0", "OFF"),
              QuickChoice("2", "2 chunks"),
              QuickChoice("3", "3 chunks"),
              QuickChoice("4", "4 chunks"),
              QuickChoice("5", "5 chunks"),
            ),
        )

      START_PAGE_KEY ->
        QuickChoiceSpec(
          title = "시작 페이지",
          selectedValue =
            prefs
              .getString(START_PAGE_KEY, START_PAGE_MYBOOK)
              ?.takeIf { it in START_PAGES }
              ?: START_PAGE_MYBOOK,
          choices =
            listOf(
              QuickChoice(START_PAGE_HOME, "홈"),
              QuickChoice(START_PAGE_LAST_VIEW, "마지막으로 본 작품"),
              QuickChoice(START_PAGE_MYBOOK, "내서재"),
            ),
        )

      "volume_behavior" ->
        QuickChoiceSpec(
          title = "볼륨 키 동작",
          selectedValue =
            prefs
              .getString("volume_behavior", "move_page")
              ?.takeIf { it in VOLUME_BEHAVIORS }
              ?: "move_page",
          choices =
            listOf(
              QuickChoice("move_page", "페이지 이동"),
              QuickChoice("disable", "기본 볼륨 조절"),
            ),
        )

      "volume_direction" ->
        QuickChoiceSpec(
          title = "볼륨 키 방향",
          selectedValue =
            prefs
              .getString("volume_direction", "up_prev")
              ?.takeIf { it in VOLUME_DIRECTIONS }
              ?: "up_prev",
          choices =
            listOf(
              QuickChoice("up_prev", "↑ 이전 / ↓ 다음"),
              QuickChoice("up_next", "↑ 다음 / ↓ 이전"),
            ),
        )

      else -> null
    }
  }

  @JavascriptInterface
  fun openQuickChoiceDialog(key: String) {
    activity.runOnUiThread {
      val spec = quickChoiceSpec(key) ?: return@runOnUiThread
      if (spec.choices.isEmpty()) return@runOnUiThread

      val labels = spec.choices.map { it.label }.toTypedArray()
      val checkedIndex =
        spec.choices.indexOfFirst { it.value == spec.selectedValue }.coerceAtLeast(0)

      val dialog =
        AlertDialog.Builder(activity)
          .setTitle(spec.title)
          .setSingleChoiceItems(labels, checkedIndex) { _, _ -> }
          .setNegativeButton("취소", null)
          .create()

      dialog.setOnShowListener {
        val list = dialog.listView ?: return@setOnShowListener
        list.setOnItemClickListener { _, _, position, _ ->
          val choice = spec.choices.getOrNull(position) ?: return@setOnItemClickListener
          if (setQuickSetting(key, choice.value)) {
            dialog.dismiss()
            activity.refreshViewerQuickSettingsPanel()
          }
        }
      }

      dialog.show()
    }
  }

  @JavascriptInterface
  fun openSystemTtsSettings() {
    activity.runOnUiThread {
      val candidates =
        listOf(
          Intent("com.android.settings.TTS_SETTINGS").addCategory(Intent.CATEGORY_DEFAULT),
          Intent("android.settings.TTS_SETTINGS").addCategory(Intent.CATEGORY_DEFAULT),
          Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        )
      val target = candidates.firstOrNull { it.resolveActivity(activity.packageManager) != null }
      if (target != null) {
        activity.startActivity(target)
      } else {
        Toast.makeText(
          activity,
          "이 기기에서는 시스템 TTS 설정 바로가기를 열 수 없습니다.",
          Toast.LENGTH_LONG,
        ).show()
      }
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
