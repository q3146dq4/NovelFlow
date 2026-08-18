package com.NovelRegEx.app.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray

object TtsRegexStore {
    private const val KEY_RULES = "tts_regex_rules_v1"
    private const val KEY_DEFAULT_RULES_VERSION = "tts_regex_default_rules_version"
    private const val KEY_KOREAN_NUMBER_ENABLED = "tts_regex_korean_number_enabled_v1"
    private const val DEFAULT_RULES_VERSION = 7

    private const val NUMBER_TOKEN = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"

    fun load(context: Context): MutableList<TtsRegexRule> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(KEY_RULES, null)

        if (raw == null) {
            val defaults = defaultRules().toMutableList()
            saveToPreferences(prefs, defaults)
            return defaults
        }

        val loaded =
            runCatching {
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

    fun isKoreanNumberEnabled(context: Context): Boolean =
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .getBoolean(KEY_KOREAN_NUMBER_ENABLED, true)

    fun setKoreanNumberEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        PreferenceManager
            .getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_KOREAN_NUMBER_ENABLED, enabled)
            .apply()
    }

    fun exportJson(context: Context): String {
        val array = JSONArray()
        load(context).forEach { array.put(it.toJson()) }
        return array.toString(2)
    }

    fun importJson(
        context: Context,
        json: String,
    ): Result<Int> =
        runCatching {
            val array = JSONArray(json)
            val imported = mutableListOf<TtsRegexRule>()
            for (i in 0 until array.length()) {
                val rule = TtsRegexRule.fromJson(array.getJSONObject(i))
                if (rule.pattern.isNotEmpty()) imported += rule
            }
            saveToPreferences(PreferenceManager.getDefaultSharedPreferences(context), imported)
            imported.size
        }

    /**
     * Migration to the verified rule set.
     *
     * - Exact untouched v2 defaults are upgraded in place.
     * - The broken v3 temporary whitespace IDs are repaired.
     * - User-edited rules are never overwritten.
     * - Rules deleted after v3 are not re-added.
     */
    private fun migrateDefaultRulesIfNeeded(
        prefs: SharedPreferences,
        rules: MutableList<TtsRegexRule>,
    ): MutableList<TtsRegexRule> {
        val currentVersion = prefs.getInt(KEY_DEFAULT_RULES_VERSION, 1)

        // Version 7 removes the experimental ${ko-number:N} replacement syntax
        // from all built-in defaults. Built-ins are canonicalized once so every
        // installation ends up with ordinary Java replacements ($1, $2 ...).
        // Custom rules remain untouched.
        if (currentVersion < DEFAULT_RULES_VERSION) {
            val repaired = forceRepairBuiltInDefaults(rules)
            saveToPreferences(prefs, repaired)
            return repaired
        }

        return rules
    }


    /**
     * One-time canonicalization of built-in defaults after the broken v3-v6
     * migration chain.
     *
     * Custom rules are preserved.  Every canonical built-in rule is restored
     * in canonical order, while preserving the user's enabled state when the
     * same built-in ID already exists.
     */
    private fun forceRepairBuiltInDefaults(
        rules: MutableList<TtsRegexRule>,
    ): MutableList<TtsRegexRule> {
        val defaults = defaultRules()
        val defaultIds = defaults.mapTo(linkedSetOf()) { it.id }

        val enabledById =
            rules
                .asSequence()
                .filter { it.id in defaultIds }
                .associate { it.id to it.enabled }

        val obsoleteAliasIds =
            setOf(
                "default-remove-invisible-space",
                "default-normalize-special-spaces",
            )

        val legacyDefaultSignatures =
            v2DefaultRules()
                .mapTo(hashSetOf()) { signatureOf(it) }

        val customRules =
            rules.filter { rule ->
                rule.id !in defaultIds &&
                    rule.id !in obsoleteAliasIds &&
                    signatureOf(rule) !in legacyDefaultSignatures
            }

        return buildList {
            defaults.forEach { default ->
                add(
                    default.copy(
                        enabled = enabledById[default.id] ?: default.enabled,
                    ),
                )
            }
            addAll(customRules)
        }.toMutableList()
    }

    /**
     * The first v3 build used two temporary IDs for whitespace rules. Upgrades
     * from v2 could therefore retain the old rules and append one duplicate,
     * producing 27 rules. Fresh v3 installs also missed the legacy comma rule.
     * Only untouched temporary defaults are rewritten/removed here.
     */
    private fun migrateBrokenV3Aliases(
        rules: MutableList<TtsRegexRule>,
        currentDefaults: Map<String, TtsRegexRule>,
    ) {
        val brokenInvisible =
            TtsRegexRule(
                id = "default-remove-invisible-space",
                name = "제로폭 문자 제거",
                pattern = "[\\u200B\\u200C\\u200D\\u2060\\uFEFF]",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            )
        val brokenSpecialSpaces =
            TtsRegexRule(
                id = "default-normalize-special-spaces",
                name = "특수 공백을 일반 공백으로",
                pattern = "[\\u00A0\\u2007\\u202F\\u3000]+",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            )

        val hadBrokenInvisible =
            rules.any { it.id == brokenInvisible.id && sameRuleContent(it, brokenInvisible) }

        migrateAliasRule(
            rules = rules,
            oldRule = brokenInvisible,
            newRule = currentDefaults.getValue("default-remove-zero-width-space"),
        )
        migrateAliasRule(
            rules = rules,
            oldRule = brokenSpecialSpaces,
            newRule = currentDefaults.getValue("default-normalize-nbsp"),
        )

        // A fresh install of the broken v3 set never received this disabled
        // legacy rule. Add it only when that exact broken-v3 fingerprint exists.
        if (hadBrokenInvisible && rules.none { it.id == "default-read-comma-decimal-legacy" }) {
            val commaRule = currentDefaults.getValue("default-read-comma-decimal-legacy")
            val insertAt =
                rules.indexOfFirst { it.id == "default-read-fraction" }
                    .takeIf { it >= 0 }
                    ?: rules.size
            rules.add(insertAt, commaRule)
        }
    }

    private fun migrateAliasRule(
        rules: MutableList<TtsRegexRule>,
        oldRule: TtsRegexRule,
        newRule: TtsRegexRule,
    ) {
        val oldIndex =
            rules.indexOfFirst {
                it.id == oldRule.id && sameRuleContent(it, oldRule)
            }
        if (oldIndex < 0) return

        val oldEnabled = rules[oldIndex].enabled
        val canonicalIndex = rules.indexOfFirst { it.id == newRule.id }
        if (canonicalIndex >= 0) {
            rules.removeAt(oldIndex)
        } else {
            rules[oldIndex] = newRule.copy(enabled = oldEnabled)
        }
    }

    private fun sameRuleContent(
        a: TtsRegexRule,
        b: TtsRegexRule,
    ): Boolean =
        a.pattern == b.pattern &&
            a.replacement == b.replacement &&
            a.ignoreCase == b.ignoreCase &&
            a.isRegex == b.isRegex

    private fun signatureOf(rule: TtsRegexRule): String =
        listOf(
            rule.pattern,
            rule.replacement,
            rule.ignoreCase.toString(),
            rule.isRegex.toString(),
        ).joinToString("\u0000")

    private fun saveToPreferences(
        prefs: SharedPreferences,
        rules: List<TtsRegexRule>,
    ) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }

        prefs
            .edit()
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
                pattern = "[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-remove-long-alnum",
                name = "노벨피아 유출 식별용 긴 영숫자 제거",
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
                name = "제로폭 문자 제거",
                pattern = "[\\u200B\\u200C\\u200D\\u2060\\uFEFF]",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-normalize-nbsp",
                name = "특수 공백을 일반 공백으로",
                pattern = "[\\u00A0\\u2007\\u202F\\u3000]+",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-normalize-linebreaks",
                name = "탭/줄바꿈을 공백으로",
                pattern = "[\\t\\r\\n\\u0085\\u2028\\u2029]+",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-comma-decimal-legacy",
                name = "쉼표 소수점 읽기 (기본 꺼짐)",
                pattern = "([0-9]+),([0-9]+)",
                replacement = "${'$'}1점${'$'}2",
                ignoreCase = false,
                isRegex = true,
                enabled = false,
            ),
            TtsRegexRule(
                id = "default-read-fraction",
                name = "분수 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*/\\s*($NUMBER_TOKEN)(?![0-9.,])",
                replacement = "${'$'}2분의${'$'}1",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-percent",
                name = "퍼센트(%) 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*%",
                replacement = "${'$'}1퍼센트",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-dollar-prefix",
                name = "달러(\$) 앞표기 읽기",
                pattern = "\\${'$'}\\s*($NUMBER_TOKEN)",
                replacement = "${'$'}1달러",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-dollar",
                name = "달러(\$) 뒤표기 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*\\${'$'}",
                replacement = "${'$'}1달러",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-yen-prefix",
                name = "엔(¥) 앞표기 읽기",
                pattern = "¥\\s*($NUMBER_TOKEN)",
                replacement = "${'$'}1엔",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-yen",
                name = "엔(¥) 뒤표기 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*¥",
                replacement = "${'$'}1엔",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-euro-prefix",
                name = "유로(€) 앞표기 읽기",
                pattern = "€\\s*($NUMBER_TOKEN)",
                replacement = "${'$'}1유로",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-euro",
                name = "유로(€) 뒤표기 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*€",
                replacement = "${'$'}1유로",
                ignoreCase = false,
                isRegex = true,
            ),
            unitRule("default-read-kilogram", "킬로그램(kg) 읽기", "kg", "킬로그램"),
            unitRule("default-read-kilometer", "킬로미터(km) 읽기", "km", "킬로미터"),
            unitRule("default-read-centimeter", "센티미터(cm) 읽기", "cm", "센티미터"),
            unitRule("default-read-millimeter", "밀리미터(mm) 읽기", "mm", "밀리미터"),
            unitRule("default-read-milliliter", "밀리리터(ml) 읽기", "ml", "밀리리터"),
            unitRule("default-read-meter", "미터(m) 읽기", "m", "미터"),
            unitRule("default-read-gram", "그램(g) 읽기", "g", "그램"),
            unitRule("default-read-liter", "리터(l) 읽기", "l", "리터"),
            TtsRegexRule(
                id = "default-read-decimal",
                name = "소수점 읽기",
                pattern = "(?<![0-9.${'$'}¥€])((?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\.[0-9]+)(?![0-9.A-Za-z%${'$'}¥€])",
                replacement = "${'$'}1",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-remove-thousands-separator",
                name = "천 단위 숫자 읽기",
                pattern = "(?<![0-9,${'$'}¥€])([0-9]{1,3}(?:,[0-9]{3})+)(?![0-9,A-Za-z%${'$'}¥€])",
                replacement = "${'$'}1",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-collapse-whitespace",
                name = "연속 공백 정리",
                pattern = "[\\s\\p{Z}]{2,}",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
        )

    private fun unitRule(
        id: String,
        name: String,
        symbol: String,
        koreanName: String,
    ): TtsRegexRule =
        TtsRegexRule(
            id = id,
            name = name,
            pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*$symbol(?![A-Za-z])",
            replacement = "${'$'}1$koreanName",
            ignoreCase = true,
            isRegex = true,
        )

    /**
     * Exact defaults actually shipped in v2.
     * This must stay byte-for-byte equivalent in pattern/replacement semantics
     * because migration only updates untouched defaults.
     */
    private fun v2DefaultRules(): List<TtsRegexRule> =
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
            oldV2Rule("default-read-percent", "퍼센트(%) 읽기", "([0-9.,]+)\\s*%", "${'$'}1 퍼센트", false),
            oldV2Rule("default-read-dollar", "달러($) 읽기", "([0-9.,]+)\\s*\\${'$'}", "${'$'}1 달러", false),
            oldV2Rule("default-read-yen", "엔(¥) 읽기", "([0-9.,]+)\\s*¥", "${'$'}1 엔", false),
            oldV2Rule("default-read-euro", "유로(€) 읽기", "([0-9.,]+)\\s*€", "${'$'}1 유로", false),
            oldV2Rule("default-read-kilogram", "킬로그램(kg) 읽기", "([0-9.,]+)\\s*kg\\b", "${'$'}1 킬로그램", true),
            oldV2Rule("default-read-kilometer", "킬로미터(km) 읽기", "([0-9.,]+)\\s*km\\b", "${'$'}1 킬로미터", true),
            oldV2Rule("default-read-centimeter", "센티미터(cm) 읽기", "([0-9.,]+)\\s*cm\\b", "${'$'}1 센티미터", true),
            oldV2Rule("default-read-millimeter", "밀리미터(mm) 읽기", "([0-9.,]+)\\s*mm\\b", "${'$'}1 밀리미터", true),
            oldV2Rule("default-read-milliliter", "밀리리터(ml) 읽기", "([0-9.,]+)\\s*ml\\b", "${'$'}1 밀리리터", true),
            oldV2Rule("default-read-meter", "미터(m) 읽기", "([0-9.,]+)\\s*m\\b", "${'$'}1 미터", true),
            oldV2Rule("default-read-gram", "그램(g) 읽기", "([0-9.,]+)\\s*g\\b", "${'$'}1 그램", true),
            oldV2Rule("default-read-liter", "리터(l) 읽기", "([0-9.,]+)\\s*l\\b", "${'$'}1 리터", true),
            TtsRegexRule(
                id = "default-collapse-whitespace",
                name = "연속 공백 정리",
                pattern = "\\s{2,}",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
        )

    private fun oldV2Rule(
        id: String,
        name: String,
        pattern: String,
        replacement: String,
        ignoreCase: Boolean,
    ): TtsRegexRule =
        TtsRegexRule(
            id = id,
            name = name,
            pattern = pattern,
            replacement = replacement,
            ignoreCase = ignoreCase,
            isRegex = true,
        )
}
