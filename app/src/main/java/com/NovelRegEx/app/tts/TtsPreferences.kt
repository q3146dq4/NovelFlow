package com.NovelRegEx.app.tts

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager

object TtsPreferences {
  const val KEY_CHUNK_MODE = "tts_chunk_mode"
  const val CHUNK_MODE_COMMA = "comma"
  const val CHUNK_MODE_SENTENCE = "sentence"
  const val CHUNK_MODE_PARAGRAPH = "paragraph"
  const val DEFAULT_CHUNK_MODE = CHUNK_MODE_PARAGRAPH

  const val KEY_ROLLING_PREQUEUE_DEPTH = "tts_rolling_prequeue_depth"
  const val DEFAULT_ROLLING_PREQUEUE_DEPTH = 3

  const val KEY_SPEECH_RATE = "tts_speech_rate"
  const val DEFAULT_SPEECH_RATE = 1.0f
  const val MIN_SPEECH_RATE = 0.5f
  const val MAX_SPEECH_RATE = 3.0f

  const val KEY_SLEEP_MINUTES = "tts_sleep_minutes"
  const val DEFAULT_SLEEP_MINUTES = 30

  private const val KEY_STOP_EPISODE_PREFIX = "tts_stop_episode_"

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

  fun getSpeechRate(context: Context): Float =
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
      .coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE)

  fun setSpeechRate(context: Context, value: Float) {
    PreferenceManager.getDefaultSharedPreferences(context).edit {
      putFloat(KEY_SPEECH_RATE, value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE))
    }
  }

  fun getSleepMinutes(context: Context): Int =
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .getInt(KEY_SLEEP_MINUTES, DEFAULT_SLEEP_MINUTES)
      .coerceIn(1, 1440)

  fun setSleepMinutes(context: Context, minutes: Int) {
    require(minutes in 1..1440)
    PreferenceManager.getDefaultSharedPreferences(context).edit {
      putInt(KEY_SLEEP_MINUTES, minutes)
    }
  }

  fun getStopEpisode(context: Context, novelNo: String): Int {
    if (!novelNo.matches(Regex("""\d+"""))) return 0
    return PreferenceManager
      .getDefaultSharedPreferences(context)
      .getInt(KEY_STOP_EPISODE_PREFIX + novelNo, 0)
      .coerceAtLeast(0)
  }

  fun setStopEpisode(context: Context, novelNo: String, episode: Int) {
    require(novelNo.matches(Regex("""\d+""")))
    require(episode > 0)
    PreferenceManager.getDefaultSharedPreferences(context).edit {
      putInt(KEY_STOP_EPISODE_PREFIX + novelNo, episode)
    }
  }

  fun exportStopEpisodes(context: Context): Map<String, Int> =
    PreferenceManager
      .getDefaultSharedPreferences(context)
      .all
      .mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_STOP_EPISODE_PREFIX)) return@mapNotNull null
        val novelNo = key.removePrefix(KEY_STOP_EPISODE_PREFIX)
        val episode = value as? Int ?: return@mapNotNull null
        if (!novelNo.matches(Regex("""\d+""")) || episode <= 0) return@mapNotNull null
        novelNo to episode
      }.toMap()

  fun importStopEpisodes(context: Context, values: Map<String, Int>) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    prefs.edit {
      prefs.all.keys.filter { it.startsWith(KEY_STOP_EPISODE_PREFIX) }.forEach { remove(it) }
      values.forEach { (novelNo, episode) ->
        if (novelNo.matches(Regex("""\d+""")) && episode > 0) {
          putInt(KEY_STOP_EPISODE_PREFIX + novelNo, episode)
        }
      }
    }
  }
}
