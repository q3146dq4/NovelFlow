package com.novelflow.app.tts

import android.content.Context
import androidx.preference.PreferenceManager
import org.json.JSONArray

object TtsRegexStore {
    private const val KEY_RULES = "tts_regex_rules_v1"

    fun load(context: Context): MutableList<TtsRegexRule> {
        val raw = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_RULES, null) ?: return defaultRules().toMutableList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val rule = TtsRegexRule.fromJson(array.getJSONObject(i))
                    if (rule.pattern.isNotEmpty()) add(rule)
                }
            }.toMutableList()
        }.getOrElse { defaultRules().toMutableList() }
    }

    fun save(context: Context, rules: List<TtsRegexRule>) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(KEY_RULES, array.toString())
            .apply()
    }

    fun reset(context: Context) {
        save(context, defaultRules())
    }

    fun exportJson(context: Context): String {
        val array = JSONArray()
        load(context).forEach { array.put(it.toJson()) }
        return array.toString(2)
    }

    fun importJson(context: Context, json: String): Result<Int> = runCatching {
        val array = JSONArray(json)
        val imported = mutableListOf<TtsRegexRule>()
        for (i in 0 until array.length()) {
            val rule = TtsRegexRule.fromJson(array.getJSONObject(i))
            if (rule.pattern.isNotEmpty()) imported += rule
        }
        save(context, imported)
        imported.size
    }

    private fun defaultRules(): List<TtsRegexRule> = listOf(
        TtsRegexRule(
            name = "커버 접기/보기 제거",
            pattern = "커버\\s*(?:접기|보기)",
            replacement = "",
            ignoreCase = false,
            isRegex = true,
        ),
        TtsRegexRule(
            name = "한자 제거",
            pattern = "[一-龥]",
            replacement = "",
            ignoreCase = false,
            isRegex = true,
        ),
        TtsRegexRule(
            name = "긴 영문/숫자 문자열 제거",
            pattern = "[a-zA-Z0-9]{15,}",
            replacement = "",
            ignoreCase = false,
            isRegex = true,
        ),
    )
}
