package com.NovelRegEx.app.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsChunkBuilderTest {
  @Test
  fun longSingleSentenceIsSplitBelowAndroidLimitWithoutLoss() {
    val source = "가".repeat(95)
    val chunks =
      TtsChunkBuilder.build(
        sentences = listOf(TtsChunkSourceSentence(line = 0, text = source)),
        mode = TtsPreferences.CHUNK_MODE_SENTENCE,
        maxInputLength = 20,
        prepareText = { it },
      )

    assertTrue(chunks.size > 1)
    assertTrue(chunks.all { it.text.length <= 20 })
    assertEquals(source, chunks.joinToString(separator = "") { it.text })
  }

  @Test
  fun unicodeLettersAreSpeakable() {
    val source = "こんにちは世界"
    val chunks =
      TtsChunkBuilder.build(
        sentences = listOf(TtsChunkSourceSentence(line = 0, text = source)),
        mode = TtsPreferences.CHUNK_MODE_SENTENCE,
        maxInputLength = 100,
        prepareText = { it },
      )

    assertEquals(1, chunks.size)
    assertEquals(source, chunks.single().text)
  }

  @Test
  fun commaModeUsesJavascriptProvidedPartsAsSourceOfTruth() {
    val sentence =
      TtsChunkSourceSentence(
        line = 0,
        text = "1,2, 세 번째",
        commaParts = listOf("1,2,", "세 번째"),
      )

    val chunks =
      TtsChunkBuilder.build(
        sentences = listOf(sentence),
        mode = TtsPreferences.CHUNK_MODE_COMMA,
        maxInputLength = 100,
        prepareText = { it },
      )

    assertEquals(listOf("1,2,", "세 번째"), chunks.map { it.text })
    assertEquals(listOf(0, 1), chunks.map { it.commaPartIndex })
  }

  @Test
  fun paragraphModeNeverExceedsInputLimit() {
    val sentences =
      listOf(
        TtsChunkSourceSentence(line = 3, text = "첫 번째 문장입니다."),
        TtsChunkSourceSentence(line = 3, text = "두 번째 문장입니다."),
        TtsChunkSourceSentence(line = 3, text = "세 번째 문장입니다."),
      )

    val chunks =
      TtsChunkBuilder.build(
        sentences = sentences,
        mode = TtsPreferences.CHUNK_MODE_PARAGRAPH,
        maxInputLength = 20,
        prepareText = { it },
      )

    assertTrue(chunks.isNotEmpty())
    assertTrue(chunks.all { it.text.length <= 20 })
  }

  @Test
  fun hardSplitDoesNotCutEmojiSurrogatePair() {
    val source = "가".repeat(9) + "😀" + "나".repeat(9)
    val parts = TtsChunkBuilder.splitForInputLimit(source, 10)

    assertTrue(parts.all { part ->
      part.indices.none { index ->
        Character.isHighSurrogate(part[index]) &&
          (index + 1 >= part.length || !Character.isLowSurrogate(part[index + 1]))
      }
    })
  }

  @Test
  fun punctuationOnlyTextIsSkipped() {
    val chunks =
      TtsChunkBuilder.build(
        sentences = listOf(TtsChunkSourceSentence(line = 0, text = "...?!")),
        mode = TtsPreferences.CHUNK_MODE_SENTENCE,
        maxInputLength = 100,
        prepareText = { it },
      )

    assertTrue(chunks.isEmpty())
  }
}
