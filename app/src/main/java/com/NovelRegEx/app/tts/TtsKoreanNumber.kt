package com.NovelRegEx.app.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Korean pronunciation normalizer for ordinary cardinal numbers.
 *
 * - Integer part uses Sino-Korean place values: 1234 -> 천이백삼십사
 * - Decimal digits are read one by one: 3.14 -> 삼점일사
 * - Proper thousands separators are accepted: 1,234,567 -> 백이십삼만사천오백육십칠
 * - Multi-digit integers with a leading zero are left untouched because they are
 *   more likely to be IDs, phone fragments, codes, etc.
 *
 * This is deliberately NOT a regex replacement macro. Regex rules remain normal
 * Java replacements ($1, $2 ...). When the global Korean-number option is ON,
 * the final regex result is passed through normalizeText().
 */
object TtsKoreanNumber {
    private val validNumberPattern =
        Pattern.compile("^[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?$")

    // Avoid touching numbers embedded in version/IP/code-like strings.
    // Korean letters/units are intentionally allowed next to the number.
    private val numberInTextPattern =
        Pattern.compile(
            "(?<![0-9A-Za-z_.:\\-/])" +
                "([+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?)" +
                "(?![0-9A-Za-z_.:\\-/])",
        )

    private val digitNames = arrayOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
    private val smallUnits = arrayOf("", "십", "백", "천")
    private val largeUnits =
        arrayOf("", "만", "억", "조", "경", "해", "자", "양", "구", "간", "정", "재", "극")

    fun normalizeText(input: String): String {
        if (input.isBlank()) return input

        val matcher = numberInTextPattern.matcher(input)
        val output = StringBuffer(input.length + 32)
        while (matcher.find()) {
            val raw = matcher.group(1)
            val converted = toKoreanOrNull(raw) ?: raw
            matcher.appendReplacement(output, Matcher.quoteReplacement(converted))
        }
        matcher.appendTail(output)
        return output.toString()
    }

    fun toKorean(raw: String): String = toKoreanOrNull(raw) ?: raw

    private fun toKoreanOrNull(raw: String): String? {
        val original = raw.trim()
        if (original.isEmpty() || !validNumberPattern.matcher(original).matches()) return null

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

        // Preserve code-like leading-zero integers such as 001, 010, 007.
        // Decimal 0.xxx is still normalized normally.
        if (fractionPart == null && integerPart.length > 1 && integerPart.startsWith('0')) return null

        val integerText = readInteger(integerPart)
        val fractionText =
            fractionPart?.let { digits ->
                buildString {
                    append("점")
                    digits.forEach { digit ->
                        if (digit !in '0'..'9') return null
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

                // 10,000 is normally read "만", while 억/조 normally keep "일".
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
