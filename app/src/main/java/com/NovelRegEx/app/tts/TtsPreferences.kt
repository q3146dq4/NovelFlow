package com.NovelRegEx.app.tts

import android.content.Context
import androidx.preference.PreferenceManager

object TtsPreferences {
  const val KEY_CHUNK_MODE = "tts_chunk_mode"
  const val CHUNK_MODE_COMMA = "comma"
  const val CHUNK_MODE_SENTENCE = "sentence"
  const val CHUNK_MODE_PARAGRAPH = "paragraph"
  const val DEFAULT_CHUNK_MODE = CHUNK_MODE_PARAGRAPH

  const val KEY_ROLLING_PREQUEUE_DEPTH = "tts_rolling_prequeue_depth"
  const val DEFAULT_ROLLING_PREQUEUE_DEPTH = 3

  val chunkModes =
    setOf(
      CHUNK_MODE_COMMA,
      CHUNK_MODE_SENTENCE,
      CHUNK_MODE_PARAGRAPH,
    )

  val rollingPrequeueDepths = setOf(0, 2, 3, 4, 5)

  fun getChunkMode(context: Context): String {
    val value =
      PreferenceManager
        .getDefaultSharedPreferences(context)
        .getString(KEY_CHUNK_MODE, DEFAULT_CHUNK_MODE)
    return value?.takeIf { it in chunkModes } ?: DEFAULT_CHUNK_MODE
  }

  fun getRollingPrequeueDepth(context: Context): Int {
    val value =
      PreferenceManager
        .getDefaultSharedPreferences(context)
        .getString(KEY_ROLLING_PREQUEUE_DEPTH, DEFAULT_ROLLING_PREQUEUE_DEPTH.toString())
        ?.toIntOrNull()
    return value?.takeIf { it in rollingPrequeueDepths } ?: DEFAULT_ROLLING_PREQUEUE_DEPTH
  }
}
