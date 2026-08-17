package com.NovelRegEx.app.filter

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.json.JSONArray

object FilterPreferences {
  const val KEY_ENABLED = "filters_enabled"
  const val KEY_AUTO_UPDATE = "filters_auto_update"
  const val KEY_SUBSCRIPTIONS = "filters_subscriptions"
  const val KEY_USER_RULES = "filters_user_rules"
  const val KEY_LAST_UPDATED_AT = "filters_last_updated_at"
  const val KEY_LAST_UPDATE_ERROR = "filters_last_update_error"

  private const val DEFAULT_SUBSCRIPTION_URL = "https://cdn.jsdelivr.net/npm/@list-kr/filterslists@latest/dist/filterslist-AdGuard.txt"
  private const val UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L
  val DEFAULT_SUBSCRIPTIONS: List<String> = listOf(DEFAULT_SUBSCRIPTION_URL)

  fun prefs(context: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

  fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

  fun isAutoUpdateEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_UPDATE, true)

  fun getSubscriptionUrls(context: Context): List<String> {
    val prefs = prefs(context)
    val rawValue = prefs.getString(KEY_SUBSCRIPTIONS, null)
    val urls = parseStringList(rawValue, DEFAULT_SUBSCRIPTIONS)
    val encoded = encodeStringArray(urls)
    // 이전 버전의 줄바꿈 문자열 저장값을 JSON 배열 문자열로 정규화한다.
    if (rawValue != null && rawValue != encoded) {
      prefs.edit {
        putString(KEY_SUBSCRIPTIONS, encoded)
      }
    }
    return urls
  }

  fun getSubscriptionEditorText(context: Context): String = getSubscriptionUrls(context).joinToString("\n")

  fun setSubscriptionEditorText(
    context: Context,
    value: String,
  ) {
    setSubscriptionUrls(context, value.lineSequence().toList())
  }

  fun setSubscriptionUrls(
    context: Context,
    urls: List<String>,
  ) {
    prefs(context).edit {
      putString(KEY_SUBSCRIPTIONS, encodeStringArray(urls))
    }
  }

  fun resetSubscriptionUrls(context: Context) {
    setSubscriptionUrls(context, DEFAULT_SUBSCRIPTIONS)
  }

  fun getUserRuleLines(context: Context): List<String> {
    val prefs = prefs(context)
    val rawValue = prefs.getString(KEY_USER_RULES, null)
    val rules = parseStringList(rawValue, emptyList())
    val encoded = encodeStringArray(rules)
    // 이전 버전의 줄바꿈 문자열 저장값을 JSON 배열 문자열로 정규화한다.
    if (rawValue != null && rawValue != encoded) {
      prefs.edit {
        putString(KEY_USER_RULES, encoded)
      }
    }
    return rules
  }

  fun getUserRuleEditorText(context: Context): String = getUserRuleLines(context).joinToString("\n")

  fun setUserRulesEditorText(
    context: Context,
    value: String,
  ) {
    setUserRuleLines(context, value.lineSequence().toList())
  }

  fun setUserRuleLines(
    context: Context,
    rules: List<String>,
  ) {
    prefs(context).edit {
      putString(KEY_USER_RULES, encodeStringArray(rules))
    }
  }

  fun getLastUpdatedAt(context: Context): Long = prefs(context).getLong(KEY_LAST_UPDATED_AT, 0L)

  fun getLastUpdateError(context: Context): String? = prefs(context).getString(KEY_LAST_UPDATE_ERROR, null)

  fun shouldRefresh(
    context: Context,
    now: Long = System.currentTimeMillis(),
  ): Boolean {
    if (!isEnabled(context) || !isAutoUpdateEnabled(context)) return false
    return now - getLastUpdatedAt(context) >= UPDATE_INTERVAL_MS
  }

  private fun parseStringList(
    rawValue: String?,
    defaultValue: List<String>,
  ): List<String> {
    if (rawValue == null) return defaultValue
    if (rawValue.isBlank()) return emptyList()
    val parsed =
      runCatching {
        val array = JSONArray(rawValue)
        buildList {
          for (index in 0 until array.length()) {
            add(array.getString(index))
          }
        }
      }.getOrElse {
        // JSON 배열로 파싱되지 않으면 이전 버전의 줄바꿈 구분 문자열로 간주한다.
        rawValue.lineSequence().toList()
      }
    return normalizeStringArray(parsed)
  }

  private fun encodeStringArray(values: List<String>): String {
    val array = JSONArray()
    normalizeStringArray(values).forEach { value -> array.put(value) }
    return array.toString()
  }

  private fun normalizeStringArray(values: Iterable<String>): List<String> = values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
