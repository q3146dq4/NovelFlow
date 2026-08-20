package com.NovelRegEx.app.tts

internal data class TtsChunkSourceSentence(
  val line: Int,
  val paragraph: Int = line,
  val text: String,
  val commaParts: List<String> = emptyList(),
)

internal data class TtsBuiltChunkPart(
  val sentenceIndex: Int,
  val start: Int,
  val endExclusive: Int,
)

internal data class TtsBuiltChunk(
  val text: String,
  val startSentenceIndex: Int,
  val endSentenceIndexExclusive: Int,
  val parts: List<TtsBuiltChunkPart>,
  val commaPartIndex: Int? = null,
)

/**
 * Pure Kotlin TTS chunk builder.
 *
 * - Uses JS-provided comma parts so playback/highlight/seek share one comma split source.
 * - Enforces TextToSpeech max input length, including a single very long sentence.
 * - Treats every Unicode letter/number as speakable content.
 * - Contains no Android UI state, so it can safely run on Dispatchers.Default.
 */
internal object TtsChunkBuilder {
  private val speakableRegex = Regex("[\\p{L}\\p{N}]")

  fun containsSpeakableContent(text: String): Boolean =
    speakableRegex.containsMatchIn(text)

  fun build(
    sentences: List<TtsChunkSourceSentence>,
    mode: String,
    maxInputLength: Int,
    prepareText: (String) -> String,
  ): List<TtsBuiltChunk> {
    if (sentences.isEmpty() || maxInputLength <= 0) return emptyList()

    return when (mode) {
      TtsPreferences.CHUNK_MODE_COMMA ->
        buildCommaChunks(sentences, maxInputLength, prepareText)

      TtsPreferences.CHUNK_MODE_PARAGRAPH ->
        buildParagraphChunks(sentences, maxInputLength, prepareText)

      else ->
        buildSentenceChunks(sentences, maxInputLength, prepareText)
    }
  }

  private fun buildSentenceChunks(
    sentences: List<TtsChunkSourceSentence>,
    maxInputLength: Int,
    prepareText: (String) -> String,
  ): List<TtsBuiltChunk> =
    buildList {
      sentences.forEachIndexed { sentenceIndex, sentence ->
        val prepared = prepareText(sentence.text)
        appendStandaloneSegments(
          output = this,
          prepared = prepared,
          sentenceIndex = sentenceIndex,
          commaPartIndex = null,
          maxInputLength = maxInputLength,
        )
      }
    }

  private fun buildCommaChunks(
    sentences: List<TtsChunkSourceSentence>,
    maxInputLength: Int,
    prepareText: (String) -> String,
  ): List<TtsBuiltChunk> =
    buildList {
      sentences.forEachIndexed { sentenceIndex, sentence ->
        val commaParts =
          sentence.commaParts
            .map(String::trim)
            .filter(String::isNotEmpty)
            .ifEmpty { listOf(sentence.text) }

        commaParts.forEachIndexed { partIndex, rawPart ->
          val prepared = prepareText(rawPart)
          appendStandaloneSegments(
            output = this,
            prepared = prepared,
            sentenceIndex = sentenceIndex,
            commaPartIndex = partIndex,
            maxInputLength = maxInputLength,
          )
        }
      }
    }

  private fun buildParagraphChunks(
    sentences: List<TtsChunkSourceSentence>,
    maxInputLength: Int,
    prepareText: (String) -> String,
  ): List<TtsBuiltChunk> {
    val output = mutableListOf<TtsBuiltChunk>()
    var lineStart = 0

    while (lineStart < sentences.size) {
      val paragraph = sentences[lineStart].paragraph
      var lineEnd = lineStart + 1
      while (
        lineEnd < sentences.size &&
        sentences[lineEnd].paragraph == paragraph
      ) {
        lineEnd++
      }

      val text = StringBuilder()
      val parts = mutableListOf<TtsBuiltChunkPart>()
      var chunkStartSentence = -1
      var chunkEndSentenceExclusive = -1

      fun flush() {
        if (text.isEmpty() || parts.isEmpty()) {
          text.clear()
          parts.clear()
          chunkStartSentence = -1
          chunkEndSentenceExclusive = -1
          return
        }

        output +=
          TtsBuiltChunk(
            text = text.toString(),
            startSentenceIndex = chunkStartSentence,
            endSentenceIndexExclusive = chunkEndSentenceExclusive,
            parts = parts.toList(),
          )

        text.clear()
        parts.clear()
        chunkStartSentence = -1
        chunkEndSentenceExclusive = -1
      }

      for (sentenceIndex in lineStart until lineEnd) {
        val prepared = prepareText(sentences[sentenceIndex].text)
        if (prepared.isBlank() || !containsSpeakableContent(prepared)) continue

        val segments = splitForInputLimit(prepared, maxInputLength)

        // One source sentence itself is too long. Keep it lossless by flushing the
        // current paragraph chunk, then emit safe standalone segments for it.
        if (segments.size > 1) {
          flush()
          segments.forEach { segment ->
            if (segment.isNotBlank() && containsSpeakableContent(segment)) {
              output += standaloneChunk(segment, sentenceIndex, null)
            }
          }
          continue
        }

        val safeText = segments.firstOrNull().orEmpty()
        if (safeText.isBlank() || !containsSpeakableContent(safeText)) continue

        val separatorLength = if (text.isEmpty()) 0 else 1
        if (text.isNotEmpty() && text.length + separatorLength + safeText.length > maxInputLength) {
          flush()
        }

        if (text.isNotEmpty()) text.append(' ')
        val partStart = text.length
        text.append(safeText)

        if (chunkStartSentence < 0) chunkStartSentence = sentenceIndex
        chunkEndSentenceExclusive = sentenceIndex + 1
        parts +=
          TtsBuiltChunkPart(
            sentenceIndex = sentenceIndex,
            start = partStart,
            endExclusive = text.length,
          )
      }

      flush()
      lineStart = lineEnd
    }

    return output
  }

  private fun appendStandaloneSegments(
    output: MutableList<TtsBuiltChunk>,
    prepared: String,
    sentenceIndex: Int,
    commaPartIndex: Int?,
    maxInputLength: Int,
  ) {
    if (prepared.isBlank() || !containsSpeakableContent(prepared)) return

    splitForInputLimit(prepared, maxInputLength)
      .filter { it.isNotBlank() && containsSpeakableContent(it) }
      .forEach { segment ->
        output += standaloneChunk(segment, sentenceIndex, commaPartIndex)
      }
  }

  private fun standaloneChunk(
    text: String,
    sentenceIndex: Int,
    commaPartIndex: Int?,
  ): TtsBuiltChunk =
    TtsBuiltChunk(
      text = text,
      startSentenceIndex = sentenceIndex,
      endSentenceIndexExclusive = sentenceIndex + 1,
      parts =
        listOf(
          TtsBuiltChunkPart(
            sentenceIndex = sentenceIndex,
            start = 0,
            endExclusive = text.length,
          ),
        ),
      commaPartIndex = commaPartIndex,
    )

  /**
   * Splits a TTS string without dropping non-whitespace content.
   *
   * Preference order:
   * 1. whitespace or punctuation near the input limit
   * 2. hard boundary at the limit
   *
   * A UTF-16 surrogate pair is never split in half.
   */
  fun splitForInputLimit(
    text: String,
    maxInputLength: Int,
  ): List<String> {
    require(maxInputLength > 0) { "maxInputLength must be > 0" }
    if (text.length <= maxInputLength) return listOf(text)

    val result = mutableListOf<String>()
    var cursor = 0

    while (text.length - cursor > maxInputLength) {
      val hardEnd = (cursor + maxInputLength).coerceAtMost(text.length)
      val searchFloor =
        (cursor + (maxInputLength * 0.60).toInt())
          .coerceAtLeast(cursor + 1)
          .coerceAtMost(hardEnd)

      var cut = -1
      var index = hardEnd - 1
      while (index >= searchFloor) {
        val ch = text[index]
        if (ch.isWhitespace() || ch in SAFE_BOUNDARIES) {
          cut = index + 1
          break
        }
        index--
      }

      if (cut <= cursor) cut = hardEnd

      // Do not bisect a UTF-16 surrogate pair.
      if (
        cut < text.length &&
        cut > cursor &&
        Character.isHighSurrogate(text[cut - 1]) &&
        Character.isLowSurrogate(text[cut])
      ) {
        cut--
      }
      if (cut <= cursor) cut = hardEnd

      val segment = text.substring(cursor, cut).trim()
      if (segment.isNotEmpty()) result += segment
      cursor = cut

      while (cursor < text.length && text[cursor].isWhitespace()) cursor++
    }

    val tail = text.substring(cursor).trim()
    if (tail.isNotEmpty()) result += tail

    return result.ifEmpty { listOf(text.take(maxInputLength)) }
  }

  private val SAFE_BOUNDARIES =
    setOf(
      ',',
      '.',
      '!',
      '?',
      '…',
      ';',
      ':',
      '，',
      '。',
      '、',
      '；',
      '：',
      ')',
      ']',
      '}',
      '”',
      '’',
      '」',
      '』',
    )
}
