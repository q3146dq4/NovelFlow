package com.NovelRegEx.app.tts

import android.content.Context
import androidx.preference.PreferenceManager

object TtsPreferences {
  const val KEY_CHUNK_MODE = "tts_chunk_mode"
  const val CHUNK_MODE_COMMA = "comma"
  const val CHUNK_MODE_SENTENCE = "sentence"
  const val CHUNK_MODE_PARAGRAPH = "paragraph"
  const val DEFAULT_CHUNK_MODE = CHUNK_MODE_PARAGRAPH

  val chunkModes =
    setOf(
      CHUNK_MODE_COMMA,
      CHUNK_MODE_SENTENCE,
      CHUNK_MODE_PARAGRAPH,
    )

  fun getChunkMode(context: Context): String {
    val value =
      PreferenceManager
        .getDefaultSharedPreferences(context)
        .getString(KEY_CHUNK_MODE, DEFAULT_CHUNK_MODE)
    return value?.takeIf { it in chunkModes } ?: DEFAULT_CHUNK_MODE
  }
}
