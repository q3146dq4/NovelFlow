package com.NovelRegEx.app.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * TTS regex replacement helper for `${ko-number:N}`.
 *
 * ON:
 *   3.14 -> 삼점일사
 *   14.5 -> 십사점오
 *   1,000 -> 천
 *   1,234,567 -> 백이십삼만사천오백육십칠
 *
 * OFF:
 *   the captured numeric text is kept as-is.
 */
object TtsKoreanNumber {
    private val macroPattern = Pattern.compile("\\$\\{ko-number:(\\d+)}")
    private val validNumberPattern =
        Pattern.compile("^[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?$")

    private val digitNames = arrayOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val smallUnits = arrayOf("", "십", "백", "천")
    private val largeUnits =
        arrayOf("", "만", "억", "조", "경", "해", "자", "양", "구", "간", "정", "재", "극")

    fun replaceAll(
        pattern: Pattern,
        input: String,
        replacement: String,
        koreanNumberEnabled: Boolean,
    ): String {
        if (!replacement.contains("\${ko-number:")) {
            return pattern.matcher(input).replaceAll(replacement)
        }

        val matcher = pattern.matcher(input)
        val output = StringBuffer()

        while (matcher.find()) {
            val expandedReplacement =
                expandMacros(
                    template = replacement,
                    match = matcher,
                    koreanNumberEnabled = koreanNumberEnabled,
                ) ?: return input

            matcher.appendReplacement(output, expandedReplacement)
        }

        matcher.appendTail(output)
        return output.toString()
    }

    private fun expandMacros(
        template: String,
        match: Matcher,
        koreanNumberEnabled: Boolean,
    ): String? {
        val macroMatcher = macroPattern.matcher(template)
        val result = StringBuilder(template.length + 16)
        var cursor = 0

        while (macroMatcher.find()) {
            result.append(template, cursor, macroMatcher.start())

            val groupIndex = macroMatcher.group(1).toIntOrNull() ?: return null
            if (groupIndex < 0 || groupIndex > match.groupCount()) return null

            val raw = match.group(groupIndex).orEmpty()
            val value = if (koreanNumberEnabled) toKorean(raw) else raw

            // Keep ordinary Java replacement syntax ($1 etc.) in the rest of the template,
            // while ensuring the macro-expanded text is treated literally.
            result.append(Matcher.quoteReplacement(value))
            cursor = macroMatcher.end()
        }

        result.append(template, cursor, template.length)
        return result.toString()
    }

    fun toKorean(raw: String): String {
        val original = raw.trim()
        if (original.isEmpty() || !validNumberPattern.matcher(original).matches()) return raw

        var token = original
        val sign =
            when {
                token.startsWith("-") -> {
                    token = token.substring(1)
                    "마이너스"
                }
                token.startsWith("+") -> {
                    token = token.substring(1)
                    ""
                }
                else -> ""
            }

        val normalized = token.replace(",", "")
        val dotIndex = normalized.indexOf('.')
        val integerPart = if (dotIndex >= 0) normalized.substring(0, dotIndex) else normalized
        val fractionPart = if (dotIndex >= 0) normalized.substring(dotIndex + 1) else null

        val integerText = readInteger(integerPart)
        val fractionText =
            fractionPart?.let { digits ->
                buildString {
                    append("점")
                    digits.forEach { digit ->
                        if (digit !in '0'..'9') return raw
                        append(digitNames[digit - '0'])
                    }
                }
            }.orEmpty()

        return sign + integerText + fractionText
    }

    private fun readInteger(rawDigits: String): String {
        val digits = rawDigits.trimStart('0').ifEmpty { "0" }
        if (digits == "0") return "영"
        if (digits.any { it !in '0'..'9' }) return rawDigits

        val chunks = mutableListOf<String>()
        var end = digits.length
        while (end > 0) {
            val start = (end - 4).coerceAtLeast(0)
            chunks += digits.substring(start, end)
            end = start
        }

        val highestNonZeroChunk = chunks.indexOfLast { chunk -> chunk.any { it != '0' } }
        if (highestNonZeroChunk !in largeUnits.indices) return rawDigits

        return buildString {
            for (index in highestNonZeroChunk downTo 0) {
                val chunk = chunks[index]
                if (chunk.all { it == '0' }) continue

                val chunkText = readFourDigits(chunk)
                val largeUnit = largeUnits[index]

                if (index == 1 && chunkText == "일" && index == highestNonZeroChunk) {
                    append(largeUnit)
                } else {
                    append(chunkText)
                    append(largeUnit)
                }
            }
        }
    }

    private fun readFourDigits(chunk: String): String {
        val padded = chunk.padStart(4, '0')
        return buildString {
            padded.forEachIndexed { index, char ->
                val digit = char - '0'
                if (digit == 0) return@forEachIndexed

                val unitIndex = 3 - index
                if (!(digit == 1 && unitIndex > 0)) {
                    append(digitNames[digit])
                }
                append(smallUnits[unitIndex])
            }
        }
    }
}
