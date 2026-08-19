package com.NovelRegEx.app.tts

import java.util.regex.Pattern
import org.junit.Assert.assertEquals
import org.junit.Test

class TtsKoreanNumberTest {
  @Test
  fun convertsDecimal() {
    assertEquals("삼점일사", TtsKoreanNumber.toKorean("3.14"))
    assertEquals("십사점오", TtsKoreanNumber.toKorean("14.5"))
  }

  @Test
  fun convertsGroupedInteger() {
    assertEquals("천", TtsKoreanNumber.toKorean("1,000"))
    assertEquals("백이십삼만사천오백육십칠", TtsKoreanNumber.toKorean("1,234,567"))
  }

  @Test
  fun keepsInvalidNumberUnchanged() {
    assertEquals("1,2", TtsKoreanNumber.toKorean("1,2"))
  }

  @Test
  fun koreanNumberMacroCanBeEnabledAndDisabled() {
    val pattern = Pattern.compile("값=([0-9]+(?:\\.[0-9]+)?)")

    assertEquals(
      "삼점일사",
      TtsKoreanNumber.replaceAll(
        pattern = pattern,
        input = "값=3.14",
        replacement = "\${ko-number:1}",
        koreanNumberEnabled = true,
      ),
    )

    assertEquals(
      "3.14",
      TtsKoreanNumber.replaceAll(
        pattern = pattern,
        input = "값=3.14",
        replacement = "\${ko-number:1}",
        koreanNumberEnabled = false,
      ),
    )
  }

  @Test
  fun macroAndOrdinaryReplacementGroupCanBeMixed() {
    val pattern = Pattern.compile("([A-Z]+)=([0-9]+)")

    assertEquals(
      "HP:십(HP)",
      TtsKoreanNumber.replaceAll(
        pattern = pattern,
        input = "HP=10",
        replacement = "$1:\${ko-number:2}($1)",
        koreanNumberEnabled = true,
      ),
    )
  }
}
