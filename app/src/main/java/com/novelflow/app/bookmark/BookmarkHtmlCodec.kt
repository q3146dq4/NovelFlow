package com.NovelRegEx.app.bookmark

import android.text.Html
import androidx.core.text.htmlEncode
import java.util.UUID

object BookmarkHtmlCodec {
  private val linkPattern = Regex("""<a\s+[^>]*href\s*=\s*(["'])(.*?)\1[^>]*>(.*?)</a>""", RegexOption.IGNORE_CASE)

  fun export(items: List<BookmarkItem>): String =
    buildString {
      appendLine("<!DOCTYPE NETSCAPE-Bookmark-file-1>")
      appendLine("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">")
      appendLine("<TITLE>Bookmarks</TITLE>")
      appendLine("<H1>Bookmarks</H1>")
      appendLine("<DL><p>")
      items.forEach { item ->
        append("  <DT><A HREF=\"")
        append(escapeHtml(item.url))
        append("\">")
        append(escapeHtml(item.title))
        appendLine("</A>")
      }
      appendLine("</DL><p>")
    }

  fun import(
    html: String,
    existingUrls: Set<String>,
  ): ImportResult {
    val imported = mutableListOf<BookmarkItem>()
    val knownUrls = existingUrls.toMutableSet()
    var skipped = 0

    linkPattern.findAll(html).forEach { match ->
      val rawUrl = decodeHtml(match.groupValues[2])
      val normalizedUrl = BookmarkRepository.normalizeUrl(rawUrl)
      if ((normalizedUrl == null) || knownUrls.contains(normalizedUrl)) {
        skipped += 1
        return@forEach
      }

      val title = decodeHtml(match.groupValues[3]).ifBlank { normalizedUrl }
      imported += BookmarkItem(UUID.randomUUID().toString(), title, normalizedUrl)
      knownUrls += normalizedUrl
    }

    return ImportResult(imported, skipped)
  }

  private fun escapeHtml(value: String): String = value.htmlEncode()

  private fun decodeHtml(value: String): String = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
}

data class ImportResult(
  val items: List<BookmarkItem>,
  val skippedCount: Int,
)
