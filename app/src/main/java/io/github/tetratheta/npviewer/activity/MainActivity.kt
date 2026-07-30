package io.github.tetratheta.npviewer.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.github.tetratheta.npviewer.R
import io.github.tetratheta.npviewer.filter.FilterPreferences
import io.github.tetratheta.npviewer.filter.FilterRuntime
import io.github.tetratheta.npviewer.layout.TopSwipeRefreshLayout
import io.github.tetratheta.npviewer.update.UpdateChecker
import io.github.tetratheta.npviewer.update.UpdateNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
  private lateinit var progressBar: LinearProgressIndicator
  private lateinit var swipeRefresh: TopSwipeRefreshLayout
  private lateinit var webView: WebView
  private lateinit var filterRuntime: FilterRuntime
  private val documentStartScripts by lazy { loadAssetTexts("ad-filter.js", "scroll-restore.js") }
  private val bookmarkLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_SELECTED_URL) ?: return@registerForActivityResult
      saveScrollPosition()
      webView.loadUrl(url)
    }

  /** 페이지별 스크롤 위치 캐시 (Least Recently Used 방식) */
  private val scrollPositions = LinkedHashMap<String, Int>(16, 0.75f, true)

  /** 문서 시작 스크립트 API 지원 여부 */
  private val supportsDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
  private var lastBackPress = 0L
  private var restoringFromViewer = false

  companion object {
    private const val DEFAULT_START_PAGE_URL = "https://novelpia.com/mybook"
    private const val MAX_SCROLL_CACHE = 10
    private const val START_PAGE_KEY = "start_page"
    private const val VIEWER_URL_PART = "novelpia.com/viewer/"
  }

  /** 주입 스크립트가 이전 스크롤 위치를 조회할 때 사용하는 브리지 */
  inner class ScrollRestoreInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getScrollY(url: String): Int {
      if (!restoringFromViewer) return 0
      restoringFromViewer = false
      return scrollPositions[url] ?: 0
    }
  }

  /** 주입 스크립트가 cosmetic payload를 가져갈 때 사용하는 브리지 */
  inner class FilterCssInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getCosmetic(url: String): String {
      val cosmetic = filterRuntime.getCosmeticForUrl(url)
      return org.json
        .JSONObject()
        .put("css", cosmetic.css)
        .put("selectors", org.json.JSONArray(cosmetic.selectors))
        .toString()
    }
  }

  @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)

    bindViews()
    filterRuntime = FilterRuntime.getInstance(this)
    setupEdgeToEdge()
    setupWebView()
    setupListeners()
    setupUpdate()
    setupFilters()

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      webView.loadUrl(intent?.data?.toString() ?: getStartPageUrl())
    }

    setupBackHandler()
  }

  // region 설정

  private fun bindViews() {
    progressBar = findViewById(R.id.progress_bar)
    swipeRefresh = findViewById(R.id.swipe_refresh)
    webView = findViewById(R.id.webview)
  }

  private fun setupEdgeToEdge() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
  private fun setupWebView() {
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
    }

    webView.addJavascriptInterface(ScrollRestoreInterface(), "_ScrollRestore")
    webView.addJavascriptInterface(FilterCssInterface(), "_AdFilter")

    // 문서 시작 스크립트를 지원하면 스크롤 복원과 cosmetic 적용 스크립트를 조기에 등록한다.
    if (supportsDocumentStartScript) {
      documentStartScripts.forEach { script ->
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
      }
    }

    webView.webChromeClient =
      object : WebChromeClient() {
        override fun onProgressChanged(
          view: WebView,
          newProgress: Int,
        ) {
          progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
          progressBar.progress = newProgress
        }
      }

    webView.webViewClient =
      object : WebViewClient() {
        override fun shouldInterceptRequest(
          view: WebView,
          request: WebResourceRequest,
        ): WebResourceResponse? {
          if (request.isForMainFrame) {
            filterRuntime.preparePage(request.url.toString())
          }
          return filterRuntime.maybeBlock(request)
        }

        override fun shouldOverrideUrlLoading(
          view: WebView,
          request: WebResourceRequest,
        ): Boolean {
          val host = request.url.host ?: return false
          if (host.endsWith("novelpia.com")) {
            saveScrollPosition()
            return false
          } // 외부 링크는 기본 브라우저로
          startActivity(Intent(Intent.ACTION_VIEW, request.url))
          return true
        }

        override fun onPageFinished(
          view: WebView,
          url: String?,
        ) {
          swipeRefresh.isRefreshing = false

          if (url != null) {
            lifecycleScope.launch {
              withContext(Dispatchers.IO) {
                filterRuntime.preparePage(url)
              }
              if (view.url == url) {
                if (supportsDocumentStartScript) {
                  refreshCosmeticFilters(view)
                } else { // 미지원 환경은 페이지 완료 후 정적 자산 스크립트를 순서대로 주입한다.
                  injectWebViewScript(view)
                }
              }
            }
          }
        }
      }
  }

  private fun setupListeners() {
    swipeRefresh.setOnRefreshListener { webView.reload() }

    webView.setOnLongClickListener {
      showMainMenu()
      true
    }
  }

  private fun showMainMenu() {
    val menuItems =
      listOf(
        MainMenuItem.QuickActions(
          listOf(
            MainMenuAction.Back,
            MainMenuAction.Forward,
            MainMenuAction.Refresh,
            MainMenuAction.Bookmarks,
            MainMenuAction.StartPage,
          ),
        ),
        MainMenuItem.Divider,
        MainMenuItem.Action(
          R.drawable.ic_settings_24,
          getString(R.string.menu_settings),
          MainMenuAction.Settings,
        ),
      )
    var dialog: AlertDialog? = null
    AlertDialog
      .Builder(this)
      .setAdapter(
        MainMenuAdapter(menuItems) { action ->
          dialog?.dismiss()
          handleMainMenuAction(action)
        },
      ) { _, which ->
        when (menuItems[which]) {
          is MainMenuItem.Action -> {
            dialog?.dismiss()
            handleMainMenuAction((menuItems[which] as MainMenuItem.Action).action)
          }

          is MainMenuItem.QuickActions,
          MainMenuItem.Divider,
          -> {
            // KtLint somehow forces me to use this format and warns me about unused Unit at the same time. Stupid tool...
            @Suppress("UnusedExpression")
            Unit
          }
        }
      }.show()
      .also { dialog = it }
  }

  private fun handleMainMenuAction(action: MainMenuAction) {
    when (action) {
      MainMenuAction.Back -> goBackInWebView()
      MainMenuAction.Forward -> goForwardInWebView()
      MainMenuAction.Refresh -> webView.reload()
      MainMenuAction.Bookmarks -> openBookmarks()
      MainMenuAction.StartPage -> openStartPage()
      MainMenuAction.Settings -> startActivity(Intent(this, SettingsActivity::class.java))
    }
  }

  private fun goBackInWebView() {
    if (!webView.canGoBack()) return
    if (webView.url?.contains(VIEWER_URL_PART) == true) {
      restoringFromViewer = true
    }
    saveScrollPosition()
    webView.goBack()
  }

  private fun goForwardInWebView() {
    if (!webView.canGoForward()) return
    saveScrollPosition()
    webView.goForward()
  }

  private fun openBookmarks() {
    bookmarkLauncher.launch(
      Intent(this, BookmarksActivity::class.java)
        .putExtra(BookmarksActivity.EXTRA_CURRENT_TITLE, webView.title.orEmpty())
        .putExtra(BookmarksActivity.EXTRA_CURRENT_URL, webView.url.orEmpty()),
    )
  }

  private fun openStartPage() {
    saveScrollPosition()
    webView.loadUrl(getStartPageUrl())
  }

  private inner class MainMenuAdapter(
    private val items: List<MainMenuItem>,
    private val onActionClick: (MainMenuAction) -> Unit,
  ) : BaseAdapter() {
    override fun getCount(): Int = items.size

    override fun getItem(position: Int): MainMenuItem = items[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun isEnabled(position: Int): Boolean = items[position] !is MainMenuItem.Divider

    override fun getView(
      position: Int,
      convertView: View?,
      parent: ViewGroup,
    ): View =
      when (val item = getItem(position)) {
        is MainMenuItem.Action -> {
          val view =
            convertView?.takeIf { it.id != View.NO_ID } ?: LayoutInflater.from(parent.context).inflate(R.layout.item_icon_menu, parent, false)
          (view as TextView).bindIconMenuItem(item.iconRes, item.title)
          view
        }

        is MainMenuItem.QuickActions -> {
          createQuickActionRow(item.actions, parent)
        }

        MainMenuItem.Divider -> {
          View(parent.context).apply {
            setBackgroundColor(
              androidx.core.content.ContextCompat
                .getColor(parent.context, android.R.color.darker_gray),
            )
            layoutParams =
              ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                parent.resources.getDimensionPixelSize(R.dimen.main_long_press_menu_divider_height),
              )
          }
        }
      }

    private fun createQuickActionRow(
      actions: List<MainMenuAction>,
      parent: ViewGroup,
    ): LinearLayout =
      LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams =
          ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            parent.resources.getDimensionPixelSize(R.dimen.icon_menu_item_height),
          )
        actions.forEach { action ->
          addView(
            createQuickActionButton(action),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
          )
        }
      }

    private fun createQuickActionButton(action: MainMenuAction): ImageButton =
      ImageButton(this@MainActivity).apply {
        val selectableItemBackground = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, selectableItemBackground, true)
        setBackgroundResource(selectableItemBackground.resourceId)
        setImageResource(action.iconRes)
        scaleType = android.widget.ImageView.ScaleType.CENTER
        val title = getString(action.titleRes)
        contentDescription = title
        TooltipCompat.setTooltipText(this, title)
        isEnabled = action.isAvailable()
        alpha = if (isEnabled) 1f else 0.38f
        setOnClickListener { onActionClick(action) }
      }
  }

  private sealed interface MainMenuItem {
    data class QuickActions(
      val actions: List<MainMenuAction>,
    ) : MainMenuItem

    data class Action(
      val iconRes: Int,
      val title: String,
      val action: MainMenuAction,
    ) : MainMenuItem

    data object Divider : MainMenuItem
  }

  private enum class MainMenuAction(
    val iconRes: Int,
    val titleRes: Int,
  ) {
    Back(R.drawable.ic_arrow_back_24, R.string.menu_back),
    Forward(R.drawable.ic_arrow_forward_24, R.string.menu_forward),
    Refresh(R.drawable.ic_refresh_24, R.string.menu_refresh),
    Bookmarks(R.drawable.ic_star_24, R.string.menu_bookmarks),
    StartPage(R.drawable.ic_home_24, R.string.menu_start_page),
    Settings(R.drawable.ic_settings_24, R.string.menu_settings),
  }

  private fun MainMenuAction.isAvailable(): Boolean =
    when (this) {
      MainMenuAction.Back -> webView.canGoBack()

      MainMenuAction.Forward -> webView.canGoForward()

      MainMenuAction.Bookmarks,
      MainMenuAction.Refresh,
      MainMenuAction.StartPage,
      MainMenuAction.Settings,
      -> true
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

  private fun setupUpdate() {
    UpdateNotifier.initChannel(this)
    UpdateChecker.processAppUpdate(this)

    val autoCheck = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("auto_check_update", true)
    if (autoCheck) {
      lifecycleScope.launch { UpdateChecker.checkAndNotify(applicationContext) }
    }
  }

  private fun setupFilters() {
    lifecycleScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          if (FilterPreferences.shouldRefresh(applicationContext)) {
            filterRuntime.updateSubscriptions()
          }
          filterRuntime.refreshEngine()
        }
      }
    }
  }

  private fun setupBackHandler() {
    onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          if (webView.canGoBack()) {
            if (webView.url?.contains(VIEWER_URL_PART) == true) {
              restoringFromViewer = true
            }
            saveScrollPosition()
            webView.goBack()
            return
          }
          val now = System.currentTimeMillis()
          if (now - lastBackPress < 2000) {
            finish()
          } else {
            lastBackPress = now
            Toast.makeText(this@MainActivity, R.string.press_twice_to_exit, Toast.LENGTH_SHORT).show()
          }
        }
      },
    )
  }

  // endregion

  override fun onKeyDown(
    keyCode: Int,
    event: KeyEvent,
  ): Boolean {
    val url = webView.url ?: ""
    if (url.contains(VIEWER_URL_PART)) {
      val prefs = PreferenceManager.getDefaultSharedPreferences(this)
      if (prefs.getString("volume_behavior", "move_page") == "move_page") {
        val upPrev = prefs.getString("volume_direction", "up_prev") == "up_prev" // 볼륨 업/다운에 따라 이전/다음 페이지 클릭
        val selector =
          when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (upPrev) "#novel_drawing_left" else "#novel_drawing_right"
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (upPrev) "#novel_drawing_right" else "#novel_drawing_left"
            else -> null
          }
        if (selector != null) {
          webView.evaluateJavascript("document.querySelector('$selector')?.click()", null)
          return true
        }
      }
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    intent.data?.let { webView.loadUrl(it.toString()) }
  }

  override fun onResume() {
    super.onResume()
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    swipeRefresh.triggerFraction = prefs.getString("swipe_fraction", null)?.toFloatOrNull() ?: TopSwipeRefreshLayout.DEFAULT_TRIGGER_FRACTION
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }

  private fun saveScrollPosition() {
    val url = webView.url ?: return
    if (url.contains(VIEWER_URL_PART)) return
    if (scrollPositions.size >= MAX_SCROLL_CACHE && !scrollPositions.containsKey(url)) {
      scrollPositions.remove(scrollPositions.keys.first())
    }
    scrollPositions[url] = webView.scrollY
  }

  private fun injectWebViewScript(view: WebView) {
    documentStartScripts.forEach { script ->
      view.evaluateJavascript(script, null)
    }
  }

  private fun refreshCosmeticFilters(view: WebView) {
    view.evaluateJavascript("window.__npviewerRefreshCosmetic&&window.__npviewerRefreshCosmetic();", null)
  }

  private fun getStartPageUrl(): String {
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    return prefs.getString(START_PAGE_KEY, DEFAULT_START_PAGE_URL) ?: DEFAULT_START_PAGE_URL
  }

  @Suppress("SameParameterValue")
  private fun loadAssetText(assetName: String): String = assets.open(assetName).bufferedReader().use { it.readText() }

  @Suppress("SameParameterValue")
  private fun loadAssetTexts(vararg assetNames: String): List<String> = assetNames.map(::loadAssetText)
}
