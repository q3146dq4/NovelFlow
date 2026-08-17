package com.NovelRegEx.app.activity

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.NovelRegEx.app.R
import com.NovelRegEx.app.bookmark.BookmarkHtmlCodec
import com.NovelRegEx.app.bookmark.BookmarkItem
import com.NovelRegEx.app.bookmark.BookmarkRepository
import java.io.OutputStreamWriter

class BookmarksActivity : AppCompatActivity() {
  private lateinit var repository: BookmarkRepository
  private lateinit var adapter: BookmarkAdapter
  private lateinit var listView: ListView
  private lateinit var emptyView: TextView
  private var currentTitle: String = ""
  private var currentUrl: String = ""

  private val exportLauncher =
    registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
      if (uri != null) exportBookmarks(uri)
    }

  private val importLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) importBookmarks(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_bookmarks)

    repository = BookmarkRepository(this)
    currentTitle = intent.getStringExtra(EXTRA_CURRENT_TITLE).orEmpty()
    currentUrl = intent.getStringExtra(EXTRA_CURRENT_URL).orEmpty()

    bindViews()
    setupInsets()
    setupToolbar()
    setupList()
    refreshList()
  }

  override fun onResume() {
    super.onResume()
    if (::adapter.isInitialized) refreshList()
  }

  private fun bindViews() {
    listView = findViewById(R.id.bookmarks_list)
    emptyView = findViewById(R.id.bookmarks_empty)
  }

  private fun setupInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.bookmarks_root)) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  private fun setupToolbar() {
    val backButton = findViewById<ImageButton>(R.id.bookmarks_back)
    val addButton = findViewById<ImageButton>(R.id.bookmarks_add)
    val moreButton = findViewById<ImageButton>(R.id.bookmarks_more)

    TooltipCompat.setTooltipText(backButton, getString(R.string.tooltip_back))
    TooltipCompat.setTooltipText(addButton, getString(R.string.tooltip_add_bookmark))
    TooltipCompat.setTooltipText(moreButton, getString(R.string.tooltip_more_bookmark))

    backButton.setOnClickListener { finish() }
    addButton.setOnClickListener { showBookmarkEditor() }
    moreButton.setOnClickListener { showMoreMenu(moreButton) }
  }

  private fun setupList() {
    adapter = BookmarkAdapter()
    listView.adapter = adapter
    listView.setOnItemClickListener { _, _, position, _ ->
      val item = adapter.getItem(position)
      setResult(RESULT_OK, Intent().putExtra(EXTRA_SELECTED_URL, item.url))
      finish()
    }
    listView.setOnItemLongClickListener { _, _, position, _ ->
      showItemMenu(adapter.getItem(position))
      true
    }
  }

  private fun refreshList() {
    val items = repository.getAll()
    adapter.setItems(items)
    emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
  }

  private fun showBookmarkEditor(item: BookmarkItem? = null) {
    val density = resources.displayMetrics.density
    val container =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * density).toInt()
        setPadding(padding, (12 * density).toInt(), padding, 0)
      }
    val titleInput =
      EditText(this).apply {
        hint = getString(R.string.bookmark_title_hint)
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_NEXT
        setText(item?.title ?: currentTitle)
        singleLineSet()
      }
    val titleError =
      TextView(this).apply {
        setTextColor(ContextCompat.getColor(this@BookmarksActivity, android.R.color.holo_red_dark))
        visibility = View.GONE
      }
    val urlInput =
      EditText(this).apply {
        hint = getString(R.string.bookmark_url_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        setText(item?.url ?: currentUrl)
        singleLineSet()
      }
    val urlError =
      TextView(this).apply {
        setTextColor(ContextCompat.getColor(this@BookmarksActivity, android.R.color.holo_red_dark))
        visibility = View.GONE
      }

    container.addView(titleInput)
    container.addView(titleError)
    container.addView(urlInput)
    container.addView(urlError)

    val dialog =
      AlertDialog
        .Builder(this)
        .setTitle(if (item == null) R.string.title_add_bookmark else R.string.title_edit_bookmark)
        .setView(container)
        .setPositiveButton(android.R.string.ok, null)
        .setNegativeButton(R.string.btn_cancel, null)
        .create()

    dialog.setOnShowListener {
      val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
      val validate = {
        val title = titleInput.text.toString().trim()
        val rawUrl = urlInput.text.toString()
        val normalizedUrl = BookmarkRepository.normalizeUrl(rawUrl)
        val duplicate = normalizedUrl != null && repository.containsUrl(normalizedUrl, item?.id)

        titleError.applyError(if (title.isBlank()) getString(R.string.error_bookmark_title_required) else null)
        urlError.applyError(
          when {
            normalizedUrl == null -> getString(R.string.error_bookmark_url_invalid)
            duplicate -> getString(R.string.error_bookmark_url_duplicate)
            else -> null
          },
        )
        saveButton.isEnabled = title.isNotBlank() && normalizedUrl != null && !duplicate
      }

      titleInput.addTextChangedListener(SimpleTextWatcher { validate() })
      urlInput.addTextChangedListener(SimpleTextWatcher { validate() })
      validate()

      saveButton.setOnClickListener {
        val title = titleInput.text.toString().trim()
        val url = urlInput.text.toString()
        val saved = if (item == null) repository.add(title, url) else repository.update(item.id, title, url)
        if (saved != null) {
          refreshList()
          dialog.dismiss()
        }
      }
    }

    dialog.show()
  }

  private fun showItemMenu(item: BookmarkItem) {
    val options = arrayOf(getString(R.string.menu_edit), getString(R.string.btn_delete))
    AlertDialog
      .Builder(this)
      .setItems(options) { _, which ->
        when (which) {
          0 -> showBookmarkEditor(item)
          1 -> confirmDelete(item)
        }
      }.show()
  }

  private fun confirmDelete(item: BookmarkItem) {
    AlertDialog
      .Builder(this)
      .setTitle(R.string.title_delete_bookmark)
      .setMessage(item.title)
      .setPositiveButton(R.string.btn_delete) { _, _ ->
        repository.delete(item.id)
        refreshList()
      }.setNegativeButton(R.string.btn_cancel, null)
      .show()
  }

  private fun showMoreMenu(anchor: View) {
    val menuItems =
      listOf(
        IconMenuItem(R.drawable.ic_format_list_bulleted_24, getString(R.string.menu_edit_order)),
        IconMenuItem(R.drawable.ic_file_download_24, getString(R.string.menu_export_bookmarks)),
        IconMenuItem(R.drawable.ic_file_upload_24, getString(R.string.menu_import_bookmarks)),
      )
    val horizontalMargin = resources.getDimensionPixelSize(R.dimen.bookmark_popup_menu_horizontal_margin)
    val width =
      resources.getDimensionPixelSize(R.dimen.bookmark_popup_menu_width).coerceAtMost(resources.displayMetrics.widthPixels - horizontalMargin)
    val verticalPadding = resources.getDimensionPixelSize(R.dimen.bookmark_popup_menu_vertical_padding)
    val container =
      LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_popup_menu)
        setPadding(0, verticalPadding, 0, verticalPadding)
      }
    val popupWindow =
      PopupWindow(container, width, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
        isOutsideTouchable = true
        setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        elevation = resources.getDimension(R.dimen.bookmark_popup_menu_elevation)
      }

    menuItems.forEachIndexed { index, item ->
      container.addView(
        createPopupMenuRow(item, container) {
          popupWindow.dismiss()
          when (index) {
            0 -> startActivity(Intent(this, BookmarkOrderActivity::class.java))
            1 -> exportLauncher.launch("NovelRegEx-bookmarks.html")
            2 -> importLauncher.launch(arrayOf("text/html", "text/*", "application/octet-stream"))
          }
        },
      )
    }

    if (anchor.isAttachedToWindow) {
      popupWindow.showAsDropDown(anchor, anchor.width - width, 0)
    }
  }

  private fun createPopupMenuRow(
    item: IconMenuItem,
    parent: ViewGroup,
    onClick: () -> Unit,
  ): View {
    val view = LayoutInflater.from(this).inflate(R.layout.item_popup_icon_menu, parent, false)
    view.layoutParams =
      LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        resources.getDimensionPixelSize(R.dimen.bookmark_popup_menu_item_height),
      )
    (view as TextView).bindIconMenuItem(item.iconRes, item.title)
    view.setOnClickListener { onClick() }
    return view
  }

  private fun TextView.bindIconMenuItem(
    iconRes: Int,
    title: String,
  ) {
    text = title
    val icon =
      androidx.appcompat.content.res.AppCompatResources
        .getDrawable(context, iconRes)
    val iconSize = resources.getDimensionPixelSize(R.dimen.icon_menu_icon_size)
    icon?.setBounds(0, 0, iconSize, iconSize)
    setCompoundDrawablesRelative(icon, null, null, null)
  }

  private fun exportBookmarks(uri: Uri) {
    runCatching {
      contentResolver.openOutputStream(uri)?.use { output ->
        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
          writer.write(BookmarkHtmlCodec.export(repository.getAll()))
        }
      } ?: error("Output stream is null")
    }.onSuccess {
      Toast.makeText(this, R.string.msg_bookmarks_exported, Toast.LENGTH_SHORT).show()
    }.onFailure {
      Toast.makeText(this, R.string.msg_bookmarks_export_failed, Toast.LENGTH_SHORT).show()
    }
  }

  private fun importBookmarks(uri: Uri) {
    runCatching {
      val html = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
      val currentItems = repository.getAll()
      val result = BookmarkHtmlCodec.import(html, currentItems.map { it.url }.toSet())
      repository.saveAll(currentItems + result.items)
      result
    }.onSuccess { result ->
      refreshList()
      Toast.makeText(this, getString(R.string.msg_bookmarks_imported, result.items.size, result.skippedCount), Toast.LENGTH_SHORT).show()
    }.onFailure {
      Toast.makeText(this, R.string.msg_bookmarks_import_failed, Toast.LENGTH_SHORT).show()
    }
  }

  private fun EditText.singleLineSet() {
    isSingleLine = true
    setSelectAllOnFocus(false)
  }

  private fun TextView.applyError(message: String?) {
    text = message.orEmpty()
    visibility = if (message == null) View.GONE else View.VISIBLE
  }

  private class BookmarkAdapter : BaseAdapter() {
    private val items = mutableListOf<BookmarkItem>()

    fun setItems(nextItems: List<BookmarkItem>) {
      items.clear()
      items.addAll(nextItems)
      notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): BookmarkItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(
      position: Int,
      convertView: View?,
      parent: ViewGroup,
    ): View {
      val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_bookmark, parent, false)
      val item = getItem(position)
      view.findViewById<TextView>(R.id.bookmark_item_title).text = item.title
      view.findViewById<TextView>(R.id.bookmark_item_url).text = item.url
      return view
    }
  }

  private data class IconMenuItem(
    val iconRes: Int,
    val title: String,
  )

  private class SimpleTextWatcher(
    private val afterChanged: () -> Unit,
  ) : TextWatcher {
    override fun beforeTextChanged(
      s: CharSequence?,
      start: Int,
      count: Int,
      after: Int,
    ) = Unit

    override fun onTextChanged(
      s: CharSequence?,
      start: Int,
      before: Int,
      count: Int,
    ) = Unit

    override fun afterTextChanged(s: Editable?) = afterChanged()
  }

  companion object {
    const val EXTRA_CURRENT_TITLE = "current_title"
    const val EXTRA_CURRENT_URL = "current_url"
    const val EXTRA_SELECTED_URL = "selected_url"
  }
}
