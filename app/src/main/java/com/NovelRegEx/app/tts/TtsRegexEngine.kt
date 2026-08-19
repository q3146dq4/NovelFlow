package com.NovelRegEx.app.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Single execution path for TTS regex rules.
 * Both MainActivity playback and the regex-settings preview use this object,
 * so `${ko-number:N}` behaves identically in both places.
 */
object TtsRegexEngine {
    private val koreanNumberMacroPattern = Regex("""\$\{ko-number:\d+\}""")

    data class CompiledRule(
        val pattern: Pattern,
        val replacement: String,
        val useKoreanNumberMacro: Boolean,
    )

    fun compile(rules: List<TtsRegexRule>): List<CompiledRule> =
        rules
            .asSequence()
            .filter { it.enabled && it.pattern.isNotBlank() }
            .mapNotNull { rule ->
                runCatching {
                    val flags =
                        if (rule.ignoreCase) {
                            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                        } else {
                            Pattern.UNICODE_CASE
                        }
                    val patternText =
                        if (rule.isRegex) {
                            rule.pattern
                        } else {
                            Pattern.quote(rule.pattern)
                        }
                    val replacement =
                        if (rule.isRegex) {
                            rule.replacement
                        } else {
                            Matcher.quoteReplacement(rule.replacement)
                        }
                    CompiledRule(
                        pattern = Pattern.compile(patternText, flags),
                        replacement = replacement,
                        useKoreanNumberMacro = rule.isRegex && koreanNumberMacroPattern.containsMatchIn(rule.replacement),
                    )
                }.getOrNull()
            }.toList()

    fun apply(
        original: String,
        rules: List<TtsRegexRule>,
        koreanNumberEnabled: Boolean,
    ): String = applyCompiled(original, compile(rules), koreanNumberEnabled)

    fun applyCompiled(
        original: String,
        rules: List<CompiledRule>,
        koreanNumberEnabled: Boolean,
    ): String {
        var result = original.trim()
        if (result.isEmpty()) return result

        for (rule in rules) {
            result =
                try {
                    if (rule.useKoreanNumberMacro) {
                        TtsKoreanNumber.replaceAll(
                            pattern = rule.pattern,
                            input = result,
                            replacement = rule.replacement,
                            koreanNumberEnabled = koreanNumberEnabled,
                        )
                    } else {
                        rule.pattern.matcher(result).replaceAll(rule.replacement)
                    }
                } catch (_: Exception) {
                    // Invalid user rules must not terminate playback; VM/system errors are not swallowed.
                    result
                }
        }

        return result.trim()
    }
}
