package com.NovelRegEx.app.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.NovelRegEx.app.tts.TtsPreferences
import com.NovelRegEx.app.tts.TtsRegexStore

class TtsSettingsActivity : AppCompatActivity() {
  companion object {
    const val EXTRA_NOVEL_NO = "novel_no"
  }
  private var fragmentContainerId: Int = View.NO_ID

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    supportActionBar?.hide()

    val density = resources.displayMetrics.density
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    val header =
      LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = (56 * density).toInt()
      }

    header.addView(
      TextView(this).apply {
        text = "←"
        textSize = 25f
        gravity = Gravity.CENTER
        contentDescription = "뒤로"
        isClickable = true
        isFocusable = true
        setOnClickListener { finish() }
      },
      LinearLayout.LayoutParams((56 * density).toInt(), (56 * density).toInt()),
    )

    header.addView(
      TextView(this).apply {
        text = "TTS 설정"
        textSize = 20f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
      },
      LinearLayout.LayoutParams(0, (56 * density).toInt(), 1f),
    )

    root.addView(
      header,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
      ),
    )

    root.addView(
      View(this).apply {
        alpha = 0.12f
        setBackgroundColor(android.graphics.Color.GRAY)
      },
      LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
    )

    fragmentContainerId = View.generateViewId()
    val container = FrameLayout(this).apply { id = fragmentContainerId }
    root.addView(
      container,
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        0,
        1f,
      ),
    )

    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      header.setPadding(bars.left, bars.top, bars.right, 0)
      header.minimumHeight = (56 * density).toInt() + bars.top
      container.setPadding(bars.left, 0, bars.right, bars.bottom)
      insets
    }

    setContentView(root)
    ViewCompat.requestApplyInsets(root)

    if (savedInstanceState == null) {
      supportFragmentManager
        .beginTransaction()
        .replace(
          fragmentContainerId,
          TtsSettingsFragment().apply {
            arguments =
              Bundle().apply {
                intent
                  .getStringExtra(EXTRA_NOVEL_NO)
                  ?.takeIf { it.matches(Regex("[0-9]+")) }
                  ?.let { putString(EXTRA_NOVEL_NO, it) }
              }
          },
        )
        .commit()
    }
  }

  class TtsSettingsFragment : PreferenceFragmentCompat() {
    companion object {
      private const val TTS_ENGINE_PACKAGE_KEY = "tts_engine_package"
      private const val SYSTEM_ENGINE_VALUE = ""
    }

    private lateinit var enginePref: ListPreference
    private lateinit var koreanNumberPref: SwitchPreferenceCompat
    private lateinit var globalRegexPref: Preference
    private lateinit var novelRegexPref: Preference
    private var novelNo: String? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
      val context = requireContext()
      val screen = preferenceManager.createPreferenceScreen(context)
      novelNo =
        arguments
          ?.getString(TtsSettingsActivity.EXTRA_NOVEL_NO)
          ?.takeIf { it.matches(Regex("[0-9]+")) }
      preferenceScreen = screen

      val engineCategory = PreferenceCategory(context).apply { title = "TTS 엔진" }
      screen.addPreference(engineCategory)

      enginePref =
        ListPreference(context).apply {
          key = TTS_ENGINE_PACKAGE_KEY
          title = "사용할 TTS 엔진"
          setDefaultValue(SYSTEM_ENGINE_VALUE)
          updateEngineEntries(context, this)
          summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
            if (pref.value.isNullOrBlank()) {
              val systemPackage =
                Settings.Secure
                  .getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
                  ?.trim()
                  .orEmpty()
              val systemLabel = engineLabelForPackage(context, systemPackage)
              if (systemLabel.isNullOrBlank()) "시스템 기본 엔진" else "시스템 기본 · $systemLabel"
            } else {
              pref.entry?.toString() ?: pref.value
            }
          }
        }
      engineCategory.addPreference(enginePref)

      engineCategory.addPreference(
        Preference(context).apply {
          title = "시스템 TTS 엔진 설정"
          summary = "Android의 기본 TTS 엔진, 언어 및 음성 설정을 엽니다."
          setOnPreferenceClickListener {
            openSystemTtsSettings(context)
            true
          }
        },
      )

      val category = PreferenceCategory(context).apply { title = "재생" }
      screen.addPreference(category)

      category.addPreference(
        ListPreference(context).apply {
          key = TtsPreferences.KEY_CHUNK_MODE
          title = "TTS 청크 단위"
          entries = arrayOf("쉼표 (,)", "마침표 (.)", "문단")
          entryValues = arrayOf(
            TtsPreferences.CHUNK_MODE_COMMA,
            TtsPreferences.CHUNK_MODE_SENTENCE,
            TtsPreferences.CHUNK_MODE_PARAGRAPH,
          )
          setDefaultValue(TtsPreferences.DEFAULT_CHUNK_MODE)
          summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
            pref.entry?.toString().orEmpty()
          }
        },
      )

      category.addPreference(
        ListPreference(context).apply {
          key = TtsPreferences.KEY_ROLLING_PREQUEUE_DEPTH
          title = "Rolling Pre-Queue"
          entries = arrayOf("OFF", "2 chunks", "3 chunks", "4 chunks", "5 chunks")
          entryValues = arrayOf("0", "2", "3", "4", "5")
          setDefaultValue(TtsPreferences.DEFAULT_ROLLING_PREQUEUE_DEPTH.toString())
          summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
            pref.entry?.toString() ?: "3 chunks"
          }
        },
      )

      koreanNumberPref =
        SwitchPreferenceCompat(context).apply {
          key = "tts_korean_number_quick_setting"
          title = "한글 숫자 읽기"
          summary = "TTS 정규식의 숫자 치환을 한글 읽기로 변환합니다."
          isPersistent = false
          isChecked = TtsRegexStore.isKoreanNumberEnabled(context)
          setOnPreferenceChangeListener { _, newValue ->
            TtsRegexStore.setKoreanNumberEnabled(context, newValue as Boolean)
            true
          }
        }
      category.addPreference(koreanNumberPref)

      globalRegexPref =
        Preference(context).apply {
          title = "전역 TTS 정규식"
          setOnPreferenceClickListener {
            startActivity(
              Intent(context, TtsRegexSettingsActivity::class.java).apply {
                putExtra(TtsRegexSettingsActivity.EXTRA_SCOPE, TtsRegexSettingsActivity.SCOPE_GLOBAL)
              },
            )
            true
          }
        }
      category.addPreference(globalRegexPref)

      novelRegexPref =
        Preference(context).apply {
          title = "현재 작품 TTS 정규식"
          isEnabled = novelNo != null
          setOnPreferenceClickListener {
            val id = novelNo ?: return@setOnPreferenceClickListener true
            startActivity(
              Intent(context, TtsRegexSettingsActivity::class.java).apply {
                putExtra(TtsRegexSettingsActivity.EXTRA_SCOPE, TtsRegexSettingsActivity.SCOPE_NOVEL)
                putExtra(TtsRegexSettingsActivity.EXTRA_NOVEL_NO, id)
              },
            )
            true
          }
        }
      category.addPreference(novelRegexPref)
      refreshDynamicSummaries()
    }

    override fun onResume() {
      super.onResume()
      if (::enginePref.isInitialized) updateEngineEntries(requireContext(), enginePref)
      refreshDynamicSummaries()
    }

    private fun refreshDynamicSummaries() {
      if (
        !::koreanNumberPref.isInitialized ||
        !::globalRegexPref.isInitialized ||
        !::novelRegexPref.isInitialized
      ) return
      val context = requireContext()
      koreanNumberPref.isChecked = TtsRegexStore.isKoreanNumberEnabled(context)
      globalRegexPref.summary = "등록된 규칙 ${TtsRegexStore.load(context).size}개"
      novelRegexPref.summary =
        novelNo?.let { "등록된 규칙 ${TtsRegexStore.loadNovel(context, it).size}개" }
          ?: "뷰어에서 작품을 연 상태에서 사용할 수 있습니다."
    }

    private fun updateEngineEntries(context: Context, preference: ListPreference) {
      val selected =
        PreferenceManager
          .getDefaultSharedPreferences(context)
          .getString(TTS_ENGINE_PACKAGE_KEY, SYSTEM_ENGINE_VALUE)
          .orEmpty()

      val choices = installedTtsEngines(context)
      val labels = mutableListOf<CharSequence>("시스템 기본 엔진")
      val values = mutableListOf<CharSequence>(SYSTEM_ENGINE_VALUE)
      choices.forEach { (packageName, label) ->
        labels += label
        values += packageName
      }

      if (selected.isNotBlank() && choices.none { it.first == selected }) {
        labels += "$selected (설치되지 않음)"
        values += selected
      }

      preference.entries = labels.toTypedArray()
      preference.entryValues = values.toTypedArray()
      preference.value = selected
    }

    private fun installedTtsEngines(context: Context): List<Pair<String, String>> {
      val pm = context.packageManager
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
    private fun engineLabelForPackage(context: Context, packageName: String): String? {
      if (packageName.isBlank()) return null
      return installedTtsEngines(context).firstOrNull { it.first == packageName }?.second
        ?: runCatching {
          val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
          context.packageManager.getApplicationLabel(appInfo).toString()
        }.getOrNull()
    }

    private fun openSystemTtsSettings(context: Context) {
      val candidates = listOf(
        Intent("com.android.settings.TTS_SETTINGS").addCategory(Intent.CATEGORY_DEFAULT),
        Intent("android.settings.TTS_SETTINGS").addCategory(Intent.CATEGORY_DEFAULT),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
      )
      val target = candidates.firstOrNull { it.resolveActivity(context.packageManager) != null }
      if (target != null) {
        startActivity(target)
      } else {
        Toast.makeText(context, "이 기기에서는 시스템 TTS 설정 바로가기를 열 수 없습니다.", Toast.LENGTH_LONG).show()
      }
    }
  }
}
