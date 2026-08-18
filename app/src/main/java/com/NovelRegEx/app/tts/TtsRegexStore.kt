package com.NovelRegEx.app.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray

object TtsRegexStore {
    private const val KEY_RULES = "tts_regex_rules_v1"
    private const val KEY_DEFAULT_RULES_VERSION = "tts_regex_default_rules_version"
    private const val DEFAULT_RULES_VERSION = 2

    fun load(context: Context): MutableList<TtsRegexRule> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(KEY_RULES, null)
        if (raw == null) {
            prefs.edit().putInt(KEY_DEFAULT_RULES_VERSION, DEFAULT_RULES_VERSION).apply()
            return defaultRules().toMutableList()
        }

        val loaded = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val rule = TtsRegexRule.fromJson(array.getJSONObject(i))
                    if (rule.pattern.isNotEmpty()) add(rule)
                }
            }.toMutableList()
        }.getOrElse {
            val defaults = defaultRules().toMutableList()
            saveToPreferences(prefs, defaults)
            return defaults
        }

        return migrateDefaultRulesIfNeeded(prefs, loaded)
    }

    fun save(context: Context, rules: List<TtsRegexRule>) {
        saveToPreferences(PreferenceManager.getDefaultSharedPreferences(context), rules)
    }

    fun reset(context: Context) {
        saveToPreferences(PreferenceManager.getDefaultSharedPreferences(context), defaultRules())
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
        // Import is intentional: keep exactly the imported list and mark migration complete.
        saveToPreferences(PreferenceManager.getDefaultSharedPreferences(context), imported)
        imported.size
    }

    /**
     * v2 migrates the old hard-coded JS/Kotlin TTS pronunciation/normalization rules
     * into editable default user rules exactly once.
     *
     * After this migration, deleting a default rule does not resurrect it on restart.
     * The full set only comes back when the user explicitly resets the rules.
     */
    private fun migrateDefaultRulesIfNeeded(
        prefs: SharedPreferences,
        rules: MutableList<TtsRegexRule>,
    ): MutableList<TtsRegexRule> {
        val version = prefs.getInt(KEY_DEFAULT_RULES_VERSION, 1)
        if (version >= DEFAULT_RULES_VERSION) return rules

        val existing = rules.mapTo(mutableSetOf()) { it.signature() }
        for (rule in speechNormalizationRules()) {
            if (rule.signature() !in existing) {
                rules += rule
                existing += rule.signature()
            }
        }
        saveToPreferences(prefs, rules)
        return rules
    }

    private fun TtsRegexRule.signature(): String =
        listOf(pattern, replacement, ignoreCase.toString(), isRegex.toString()).joinToString("\u0000")

    private fun saveToPreferences(
        prefs: SharedPreferences,
        rules: List<TtsRegexRule>,
    ) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        prefs.edit()
            .putString(KEY_RULES, array.toString())
            .putInt(KEY_DEFAULT_RULES_VERSION, DEFAULT_RULES_VERSION)
            .apply()
    }

    private fun defaultRules(): List<TtsRegexRule> =
        listOf(
            TtsRegexRule(
                id = "default-remove-cover-ui",
                name = "커버 접기/보기 제거",
                pattern = "커버\\s*(?:접기|보기)",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-remove-hanja",
                name = "한자 제거",
                pattern = "[一-龥]",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-remove-long-alnum",
                name = "긴 영문/숫자 문자열 제거",
                pattern = "[a-zA-Z0-9]{15,}",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
        ) + speechNormalizationRules()

    private fun speechNormalizationRules(): List<TtsRegexRule> =
        listOf(
            TtsRegexRule(
                id = "default-remove-zero-width-space",
                name = "제로폭 공백 제거",
                pattern = "\\u200B",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-normalize-nbsp",
                name = "NBSP를 일반 공백으로",
                pattern = "\\u00A0",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-normalize-linebreaks",
                name = "탭/줄바꿈을 공백으로",
                pattern = "[\\t\\r\\n]+",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-comma-decimal-legacy",
                name = "쉼표 소수점 읽기 (기본 꺼짐)",
                pattern = "([0-9]+),([0-9]+)",
                replacement = "${'$'}1 점 ${'$'}2",
                ignoreCase = false,
                isRegex = true,
                enabled = false,
            ),
            TtsRegexRule(
                id = "default-read-fraction",
                name = "분수 읽기",
                pattern = "([0-9]+(?:[.,][0-9]+)?)\\s*/\\s*([0-9]+(?:[.,][0-9]+)?)",
                replacement = "${'$'}2분의 ${'$'}1",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-decimal",
                name = "소수점 읽기",
                pattern = "(?<![0-9])([0-9]+)\\.([0-9]+)(?![0-9])",
                replacement = "${'$'}1 점 ${'$'}2",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-remove-thousands-separator",
                name = "천 단위 쉼표 제거",
                pattern = "(?<=\\d),(?=\\d{3}(?:\\D|${'$'}))",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-percent",
                name = "퍼센트(%) 읽기",
                pattern = "([0-9.,]+)\\s*%",
                replacement = "${'$'}1 퍼센트",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-dollar",
                name = "달러($) 읽기",
                pattern = "([0-9.,]+)\\s*\\${'$'}",
                replacement = "${'$'}1 달러",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-yen",
                name = "엔(¥) 읽기",
                pattern = "([0-9.,]+)\\s*¥",
                replacement = "${'$'}1 엔",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-euro",
                name = "유로(€) 읽기",
                pattern = "([0-9.,]+)\\s*€",
                replacement = "${'$'}1 유로",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-kilogram",
                name = "킬로그램(kg) 읽기",
                pattern = "([0-9.,]+)\\s*kg\\b",
                replacement = "${'$'}1 킬로그램",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-kilometer",
                name = "킬로미터(km) 읽기",
                pattern = "([0-9.,]+)\\s*km\\b",
                replacement = "${'$'}1 킬로미터",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-centimeter",
                name = "센티미터(cm) 읽기",
                pattern = "([0-9.,]+)\\s*cm\\b",
                replacement = "${'$'}1 센티미터",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-millimeter",
                name = "밀리미터(mm) 읽기",
                pattern = "([0-9.,]+)\\s*mm\\b",
                replacement = "${'$'}1 밀리미터",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-milliliter",
                name = "밀리리터(ml) 읽기",
                pattern = "([0-9.,]+)\\s*ml\\b",
                replacement = "${'$'}1 밀리리터",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-meter",
                name = "미터(m) 읽기",
                pattern = "([0-9.,]+)\\s*m\\b",
                replacement = "${'$'}1 미터",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-gram",
                name = "그램(g) 읽기",
                pattern = "([0-9.,]+)\\s*g\\b",
                replacement = "${'$'}1 그램",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-liter",
                name = "리터(l) 읽기",
                pattern = "([0-9.,]+)\\s*l\\b",
                replacement = "${'$'}1 리터",
                ignoreCase = true,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-collapse-whitespace",
                name = "연속 공백 정리",
                pattern = "\\s{2,}",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
        )
}
