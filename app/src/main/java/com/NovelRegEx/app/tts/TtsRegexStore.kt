package com.NovelRegEx.app.tts

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray

object TtsRegexStore {
    private const val KEY_RULES = "tts_regex_rules_v1"
    private const val KEY_DEFAULT_RULES_VERSION = "tts_regex_default_rules_version"
    private const val KEY_KOREAN_NUMBER_ENABLED = "tts_regex_korean_number_enabled_v1"
    private const val DEFAULT_RULES_VERSION = 3

    private const val NUMBER_TOKEN = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?"

    fun load(context: Context): MutableList<TtsRegexRule> {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = prefs.getString(KEY_RULES, null)
        if (raw == null) {
            prefs.edit()
                .putInt(KEY_DEFAULT_RULES_VERSION, DEFAULT_RULES_VERSION)
                .apply()
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
        save(context, defaultRules())
    }

    fun isKoreanNumberEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_KOREAN_NUMBER_ENABLED, true)

    fun setKoreanNumberEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putBoolean(KEY_KOREAN_NUMBER_ENABLED, enabled)
            .apply()
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

    /**
     * v3 변경점
     * - 숫자+단위의 \\b를 한글 조사와 호환되는 (?![A-Za-z])로 변경
     * - 숫자/단위/통화 치환의 불필요한 공백 제거
     * - `${ko-number:N}` 특수 치환 도입
     * - 소수/분수/천 단위/공백/한자 범위 개선
     * - 통화 기호가 숫자 앞에 오는 표기도 기본 규칙으로 추가
     *
     * 기존 사용자가 수정한 패턴/치환은 덮어쓰지 않는다.
     * 이전 기본값과 정확히 일치하는 규칙만 새 기본값으로 1회 교체한다.
     */
    private fun migrateDefaultRulesIfNeeded(
        prefs: SharedPreferences,
        rules: MutableList<TtsRegexRule>,
    ): MutableList<TtsRegexRule> {
        val currentVersion = prefs.getInt(KEY_DEFAULT_RULES_VERSION, 1)
        if (currentVersion >= DEFAULT_RULES_VERSION) return rules

        val oldV2 = v2DefaultRules()
        val newV3 = defaultRules()
        val newById = newV3.associateBy { it.id }

        // 이전 기본값을 사용자가 손대지 않은 경우에만 교체한다.
        for (index in rules.indices) {
            val current = rules[index]
            val oldDefault =
                oldV2.firstOrNull { old ->
                    // 기존 v1 사용자는 첫 3개 규칙 ID가 UUID일 수 있어서 signature도 확인한다.
                    (old.id == current.id || sameRuleContent(old, current)) && sameRuleContent(old, current)
                } ?: continue

            val replacement = newById[oldDefault.id] ?: continue
            rules[index] = replacement.copy(
                id = current.id,
                enabled = current.enabled,
            )
        }

        val existingIds = rules.mapTo(mutableSetOf()) { it.id }
        val existingSignatures = rules.mapTo(mutableSetOf()) { signatureOf(it) }

        if (currentVersion < 2) {
            // v2 정규식 세트를 한 번도 받은 적 없는 사용자는 v3 세트를 추가한다.
            v3SpeechRules().forEach { rule ->
                if (rule.id !in existingIds && signatureOf(rule) !in existingSignatures) {
                    rules += rule
                    existingIds += rule.id
                    existingSignatures += signatureOf(rule)
                }
            }
        } else {
            // v2 사용자는 삭제한 기존 규칙을 되살리지 않고, v3에서 새로 생긴 규칙만 추가한다.
            val oldIds = oldV2.mapTo(mutableSetOf()) { it.id }
            newV3.filter { it.id !in oldIds }.forEach { rule ->
                if (rule.id !in existingIds && signatureOf(rule) !in existingSignatures) {
                    rules += rule
                    existingIds += rule.id
                    existingSignatures += signatureOf(rule)
                }
            }
        }

        saveToPreferences(prefs, rules)
        return rules
    }

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

    private data class RuleSignature(
        val pattern: String,
        val replacement: String,
        val ignoreCase: Boolean,
        val isRegex: Boolean,
    )

    private fun signatureOf(rule: TtsRegexRule): RuleSignature =
        RuleSignature(rule.pattern, rule.replacement, rule.ignoreCase, rule.isRegex)

    private fun sameRuleContent(a: TtsRegexRule, b: TtsRegexRule): Boolean =
        a.name == b.name &&
            a.pattern == b.pattern &&
            a.replacement == b.replacement &&
            a.ignoreCase == b.ignoreCase &&
            a.isRegex == b.isRegex

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
        ) + v3SpeechRules()

    private fun v3SpeechRules(): List<TtsRegexRule> =
        listOf(
            TtsRegexRule(
                id = "default-remove-invisible-space",
                name = "제로폭 문자 제거",
                pattern = "[\\u200B\\u200C\\u200D\\u2060\\uFEFF]",
                replacement = "",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-normalize-special-spaces",
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

            // 분수는 다른 숫자 규칙보다 먼저 처리한다.
            TtsRegexRule(
                id = "default-read-fraction",
                name = "분수 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*/\\s*($NUMBER_TOKEN)(?![0-9.,])",
                replacement = "${'$'}{ko-number:2}분의${'$'}{ko-number:1}",
                ignoreCase = false,
                isRegex = true,
            ),

            // 숫자와 단위/통화가 붙어 있는 경우를 단독 소수 규칙보다 먼저 처리한다.
            TtsRegexRule(
                id = "default-read-percent",
                name = "퍼센트(%) 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*%",
                replacement = "${'$'}{ko-number:1}퍼센트",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-dollar-prefix",
                name = "달러(\$) 앞표기 읽기",
                pattern = "\\${'$'}\\s*($NUMBER_TOKEN)",
                replacement = "${'$'}{ko-number:1}달러",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-dollar",
                name = "달러(\$) 뒤표기 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*\\${'$'}",
                replacement = "${'$'}{ko-number:1}달러",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-yen-prefix",
                name = "엔(¥) 앞표기 읽기",
                pattern = "¥\\s*($NUMBER_TOKEN)",
                replacement = "${'$'}{ko-number:1}엔",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-yen",
                name = "엔(¥) 뒤표기 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*¥",
                replacement = "${'$'}{ko-number:1}엔",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-euro-prefix",
                name = "유로(€) 앞표기 읽기",
                pattern = "€\\s*($NUMBER_TOKEN)",
                replacement = "${'$'}{ko-number:1}유로",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-read-euro",
                name = "유로(€) 뒤표기 읽기",
                pattern = "(?<![0-9.,])($NUMBER_TOKEN)\\s*€",
                replacement = "${'$'}{ko-number:1}유로",
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
                replacement = "${'$'}{ko-number:1}",
                ignoreCase = false,
                isRegex = true,
            ),
            TtsRegexRule(
                id = "default-remove-thousands-separator",
                name = "천 단위 숫자 읽기",
                pattern = "(?<![0-9,${'$'}¥€])([0-9]{1,3}(?:,[0-9]{3})+)(?![0-9,A-Za-z%${'$'}¥€])",
                replacement = "${'$'}{ko-number:1}",
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
            replacement = "${'$'}{ko-number:1}$koreanName",
            ignoreCase = true,
            isRegex = true,
        )

    /** 현재 v2에서 배포된 기본값. v3 마이그레이션 비교 전용이다. */
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
                id = "default-remove-invisible-space",
                name = "제로폭/NBSP 공백 제거",
                pattern = "[\\u200B\\u00A0]",
                replacement = "",
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
            oldV2UnitOrSymbolRule("default-read-percent", "퍼센트(%) 읽기", "([0-9.]+)\\s*%", "${'$'}1 퍼센트", false),
            oldV2UnitOrSymbolRule("default-read-dollar", "달러(\$) 읽기", "([0-9.]+)\\s*\\${'$'}", "${'$'}1 달러", false),
            oldV2UnitOrSymbolRule("default-read-yen", "엔(¥) 읽기", "([0-9.]+)\\s*¥", "${'$'}1 엔", false),
            oldV2UnitOrSymbolRule("default-read-euro", "유로(€) 읽기", "([0-9.]+)\\s*€", "${'$'}1 유로", false),
            oldV2UnitOrSymbolRule("default-read-kilogram", "킬로그램(kg) 읽기", "([0-9.]+)\\s*kg", "${'$'}1 킬로그램", true),
            oldV2UnitOrSymbolRule("default-read-kilometer", "킬로미터(km) 읽기", "([0-9.]+)\\s*km", "${'$'}1 킬로미터", true),
            oldV2UnitOrSymbolRule("default-read-centimeter", "센티미터(cm) 읽기", "([0-9.]+)\\s*cm", "${'$'}1 센티미터", true),
            oldV2UnitOrSymbolRule("default-read-millimeter", "밀리미터(mm) 읽기", "([0-9.]+)\\s*mm", "${'$'}1 밀리미터", true),
            oldV2UnitOrSymbolRule("default-read-milliliter", "밀리리터(ml) 읽기", "([0-9.]+)\\s*ml\\b", "${'$'}1 밀리리터", true),
            oldV2UnitOrSymbolRule("default-read-meter", "미터(m) 읽기", "([0-9.]+)\\s*m\\b", "${'$'}1 미터", true),
            oldV2UnitOrSymbolRule("default-read-gram", "그램(g) 읽기", "([0-9.]+)\\s*g\\b", "${'$'}1 그램", true),
            oldV2UnitOrSymbolRule("default-read-liter", "리터(l) 읽기", "([0-9.]+)\\s*l\\b", "${'$'}1 리터", true),
            TtsRegexRule(
                id = "default-collapse-whitespace",
                name = "연속 공백 정리",
                pattern = "\\s{2,}",
                replacement = " ",
                ignoreCase = false,
                isRegex = true,
            ),
        )

    private fun oldV2UnitOrSymbolRule(
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
