package com.novelflow.app.bookmark

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class BookmarkRepository(
  context: Context,
) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getAll(): List<BookmarkItem> {
    val raw = prefs.getString(KEY_BOOKMARKS, null) ?: return emptyList()
    return runCatching {
      val array = JSONArray(raw)
      buildList {
        for (index in 0 until array.length()) {
          val item = array.optJSONObject(index) ?: continue
          val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
          val title = item.optString("title").trim()
          val url = item.optString("url").trim()
          if (title.isNotBlank() && url.isValidBookmarkUrl()) {
            add(BookmarkItem(id, title, normalizeUrl(url) ?: url))
          }
        }
      }
    }.getOrDefault(emptyList())
  }

  fun saveAll(items: List<BookmarkItem>) {
    val array = JSONArray()
    items.forEach { item ->
      array.put(
        JSONObject()
          .put("id", item.id)
          .put("title", item.title)
          .put("url", item.url),
      )
    }
    prefs.edit { putString(KEY_BOOKMARKS, array.toString()) }
  }

  fun add(
    title: String,
    url: String,
  ): BookmarkItem? {
    val normalizedUrl = normalizeUrl(url) ?: return null
    val items = getAll()
    if (items.any { it.url == normalizedUrl }) return null
    val item = BookmarkItem(UUID.randomUUID().toString(), title.trim(), normalizedUrl)
    saveAll(items + item)
    return item
  }

  fun update(
    id: String,
    title: String,
    url: String,
  ): BookmarkItem? {
    val normalizedUrl = normalizeUrl(url) ?: return null
    val items = getAll()
    if (items.any { it.id != id && it.url == normalizedUrl }) return null
    var updated: BookmarkItem? = null
    val nextItems =
      items.map { item ->
        if (item.id == id) {
          BookmarkItem(id, title.trim(), normalizedUrl).also { updated = it }
        } else {
          item
        }
      }
    if (updated == null) return null
    saveAll(nextItems)
    return updated
  }

  fun delete(id: String) {
    saveAll(getAll().filterNot { it.id == id })
  }

  fun containsUrl(
    url: String,
    exceptId: String? = null,
  ): Boolean {
    val normalizedUrl = normalizeUrl(url) ?: return false
    return getAll().any { it.id != exceptId && it.url == normalizedUrl }
  }

  companion object {
    private const val PREFS_NAME = "bookmarks"
    private const val KEY_BOOKMARKS = "items"
    private const val BASE_URL = "https://novelpia.com/"
    private const val HOME_URL_WITHOUT_SLASH = "https://novelpia.com"

    fun normalizeUrl(value: String): String? {
      val trimmed = value.trim()
      if (trimmed == HOME_URL_WITHOUT_SLASH) return BASE_URL
      if (!trimmed.startsWith(BASE_URL)) return null
      return trimmed
    }

    fun String.isValidBookmarkUrl(): Boolean = normalizeUrl(this) != null
  }
}
