package com.NovelRegEx.app.tts

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * TTS 정규식 replacement에서 `${ko-number:N}` 매크로를 처리한다.
 *
 * 기능 ON:
 *   `${ko-number:1}` -> 캡처 그룹 1을 한국어 한자어 수사로 변환
 * 기능 OFF:
 *   `${ko-number:1}` -> 캡처 그룹 1의 원문을 그대로 사용
 *
 * 예:
 *   3.14       -> 삼점일사
 *   14.5       -> 십사점오
 *   1,000      -> 천
 *   8,884,844  -> 팔백팔십팔만사천팔백사십사
 */
object TtsKoreanNumber {
  private val specialReplacementPattern = Pattern.compile("\\$\\{ko-number:(\\d+)}")

  private val digitNames = arrayOf("영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
  private val smallUnits = arrayOf("", "십", "백", "천")
  private val largeUnits = arrayOf("", "만", "억", "조", "경", "해", "자", "양", "구", "간", "정", "재", "극")

  private val validNumberPattern =
    Pattern.compile("^[+-]?(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]+)?$")

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
      val expanded = expandReplacement(replacement, matcher, koreanNumberEnabled)
      matcher.appendReplacement(output, expanded)
    }
    matcher.appendTail(output)
    return output.toString()
  }

  fun toKorean(raw: String): String {
    val original = raw.trim()
    if (original.isEmpty() || !validNumberPattern.matcher(original).matches()) return raw

    var token = original
    val sign =
      when {
        token.startsWith('-') -> {
          token = token.substring(1)
          "마이너스"
        }
        token.startsWith('+') -> {
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
          digits.forEach { digit -> append(digitNames[digit - '0']) }
        }
      }.orEmpty()

    return sign + integerText + fractionText
  }

  private fun expandReplacement(
    template: String,
    match: Matcher,
    koreanNumberEnabled: Boolean,
  ): String {
    val tokenMatcher = specialReplacementPattern.matcher(template)
    val expanded = StringBuffer()

    while (tokenMatcher.find()) {
      val groupIndex = tokenMatcher.group(1).toInt()
      require(groupIndex <= match.groupCount()) {
        "ko-number group $groupIndex does not exist; groupCount=${match.groupCount()}"
      }

      val raw = match.group(groupIndex).orEmpty()
      val value = if (koreanNumberEnabled) toKorean(raw) else raw

      // 1차: 나중에 원래 matcher.appendReplacement()가 value 안의 $/\\를 문자로 보도록 escape.
      val escapedForOriginalMatcher = Matcher.quoteReplacement(value)
      // 2차: 위 escape 문자열을 현재 tokenMatcher.appendReplacement()에 안전하게 삽입.
      tokenMatcher.appendReplacement(expanded, Matcher.quoteReplacement(escapedForOriginalMatcher))
    }
    tokenMatcher.appendTail(expanded)
    return expanded.toString()
  }

  private fun readInteger(rawDigits: String): String {
    val digits = rawDigits.trimStart('0').ifEmpty { "0" }
    if (digits == "0") return "영"

    val chunks = mutableListOf<String>()
    var end = digits.length
    while (end > 0) {
      val start = (end - 4).coerceAtLeast(0)
      chunks += digits.substring(start, end)
      end = start
    }

    val highestNonZeroChunk = chunks.indexOfLast { chunk -> chunk.any { it != '0' } }
    if (highestNonZeroChunk >= largeUnits.size) return rawDigits

    return buildString {
      for (index in highestNonZeroChunk downTo 0) {
        val chunk = chunks[index]
        if (chunk.all { it == '0' }) continue

        val chunkText = readFourDigits(chunk)
        val largeUnit = largeUnits[index]

        if (index == 1 && chunkText == "일" && index == highestNonZeroChunk) {
          // 10,000은 보통 "일만"보다 "만"으로 읽는다.
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
