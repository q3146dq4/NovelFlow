package com.NovelRegEx.app.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Single execution path for TTS regex rules.
 *
 * Regex replacements use only ordinary Java replacement syntax ($1, $2 ...).
 * There is no custom ${ko-number:N} replacement language anymore.
 * If Korean-number normalization is enabled, the completed regex result is
 * normalized once by TtsKoreanNumber.normalizeText().
 */
object TtsRegexEngine {
    private val legacyKoNumberMacro = Regex("""\$\{ko-number:(\d+)}""")

    private fun normalizeLegacyReplacement(replacement: String): String =
        legacyKoNumberMacro.replace(replacement) { match ->
            "\$${match.groupValues[1]}"
        }

    data class CompiledRule(
        val pattern: Pattern,
        val replacement: String,
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
                            normalizeLegacyReplacement(rule.replacement)
                        } else {
                            Matcher.quoteReplacement(rule.replacement)
                        }
                    CompiledRule(
                        pattern = Pattern.compile(patternText, flags),
                        replacement = replacement,
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
                    rule.pattern.matcher(result).replaceAll(rule.replacement)
                } catch (_: Throwable) {
                    // User-editable rules must not terminate playback or settings.
                    result
                }
        }

        result = result.trim()
        if (!koreanNumberEnabled || result.isEmpty()) return result
        return TtsKoreanNumber.normalizeText(result).trim()
    }
}
