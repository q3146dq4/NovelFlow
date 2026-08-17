package com.NovelRegEx.app.activity

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
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
import com.NovelRegEx.app.R
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.filter.FilterRuntime
import com.NovelRegEx.app.layout.TopSwipeRefreshLayout
import com.NovelRegEx.app.update.UpdateChecker
import com.NovelRegEx.app.update.UpdateNotifier
import com.NovelRegEx.app.tts.TtsRegexStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
  private lateinit var progressBar: LinearProgressIndicator
  private lateinit var swipeRefresh: TopSwipeRefreshLayout
  private lateinit var webView: WebView
  private lateinit var preloadWebView: WebView
  private lateinit var filterRuntime: FilterRuntime

  private val documentStartScripts by lazy {
    loadAssetTexts("ad-filter.js", "scroll-restore.js")
  }

  private val ttsScript by lazy {
    loadAssetText("NovelRegEx-tts.js")
  }

  private val bookmarkLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val url = result.data?.getStringExtra(BookmarksActivity.EXTRA_SELECTED_URL) ?: return@registerForActivityResult
      saveScrollPosition()
      webView.loadUrl(url)
    }

  private val scrollPositions = LinkedHashMap<String, Int>(16, 0.75f, true)
  private val supportsDocumentStartScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

  private var lastBackPress = 0L
  private var restoringFromViewer = false
  private var currentPageIsViewer = false
  private var visibleViewerReady = false

  private lateinit var ttsController: NovelTtsController

  private val ttsControlReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        "ACTION_TOGGLE_PLAY" -> ttsController.togglePlayPause()
        "ACTION_NEXT" -> ttsController.next()
        "ACTION_PREV" -> ttsController.previous()
      }
    }
  }

  companion object {
    private const val DEFAULT_START_PAGE_URL = "https://novelpia.com/mybook"
    private const val MAX_SCROLL_CACHE = 10
    private const val START_PAGE_KEY = "start_page"
    private const val VIEWER_URL_PART = "novelpia.com/viewer/"
  }

  private data class TtsSentence(val line: Int, val text: String)

  inner class ScrollRestoreInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getScrollY(url: String): Int {
      if (!restoringFromViewer) return 0
      restoringFromViewer = false
      return scrollPositions[url] ?: 0
    }
  }

  inner class FilterCssInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getCosmetic(url: String): String {
      val cosmetic = filterRuntime.getCosmeticForUrl(url)
      return org.json.JSONObject()
        .put("css", cosmetic.css)
        .put("selectors", org.json.JSONArray(cosmetic.selectors))
        .toString()
    }
  }

  inner class TtsRegexJavascriptInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getRulesJson(): String = TtsRegexStore.exportJson(this@MainActivity)
  }

  inner class TtsJavascriptInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun open() { runOnUiThread { ttsController.openAndStart() } }
    @Suppress("unused")
    @JavascriptInterface
    fun stop() { runOnUiThread { ttsController.stop() } }
  }

  @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContentView(R.layout.activity_main)

    bindViews()
    filterRuntime = FilterRuntime.getInstance(this)
    ttsController = NovelTtsController()

    setupEdgeToEdge()
    setupWebView()
    setupPreloadWebView()
    setupListeners()
    setupUpdate()
    setupFilters()

    val filter = IntentFilter().apply {
      addAction("ACTION_TOGGLE_PLAY")
      addAction("ACTION_NEXT")
      addAction("ACTION_PREV")
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      registerReceiver(ttsControlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
      if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
          requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
      }
    } else {
      registerReceiver(ttsControlReceiver, filter)
    }

    if (savedInstanceState != null) {
      webView.restoreState(savedInstanceState)
    } else {
      webView.loadUrl(intent?.data?.toString() ?: getStartPageUrl())
    }
    setupBackHandler()
  }

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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
    }

    webView.addJavascriptInterface(ScrollRestoreInterface(), "_ScrollRestore")
    webView.addJavascriptInterface(FilterCssInterface(), "_AdFilter")
    webView.addJavascriptInterface(TtsJavascriptInterface(), "_NPTTS")
    webView.addJavascriptInterface(TtsRegexJavascriptInterface(), "NPTtsRegex")

    if (supportsDocumentStartScript) {
      documentStartScripts.forEach { script ->
        WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
      }
    }

    webView.webChromeClient = object : WebChromeClient() {
      override fun onProgressChanged(view: WebView, newProgress: Int) {
        progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
        progressBar.progress = newProgress
      }
    }

    webView.webViewClient = object : WebViewClient() {
      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        currentPageIsViewer = url?.contains(VIEWER_URL_PART) == true
        visibleViewerReady = false
        if (currentPageIsViewer) {
          ttsController.markPageStarted(url)
        } else {
          ttsController.close()
        }
        super.onPageStarted(view, url, favicon)
      }

      override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        currentPageIsViewer = url?.contains(VIEWER_URL_PART) == true
        if (!currentPageIsViewer) ttsController.close()
      }

      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (currentPageIsViewer) return null
        if (request.isForMainFrame) { filterRuntime.preparePage(request.url.toString()) }
        return filterRuntime.maybeBlock(request)
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host ?: return false
        if (host.endsWith("novelpia.com")) {
          saveScrollPosition()
          return false
        }
        startActivity(Intent(Intent.ACTION_VIEW, request.url))
        return true
      }

      override fun onPageFinished(view: WebView, url: String?) {
        swipeRefresh.isRefreshing = false
        if (url == null) return

        lifecycleScope.launch {
          withContext(Dispatchers.IO) { filterRuntime.preparePage(url) }
          if (view.url != url) return@launch

          if (url.contains(VIEWER_URL_PART)) {
            installTtsScript(view)
            injectBottomListenButton(view)
            visibleViewerReady = true
            ttsController.onViewerPageReady()
          } else {
            if (supportsDocumentStartScript) refreshCosmeticFilters(view)
            else injectWebViewScript(view)
          }
        }
      }
    }
  }

  private fun injectBottomListenButton(view: WebView) {
    val js = """
      (function() {
        if (window.__npTtsBtnInterval) return;
        window.__npTtsBtnInterval = setInterval(function() {
          if (document.getElementById('np-tts-injected-btn')) return;

          var buttons = document.querySelectorAll('li, a, div, span');
          var recommendBtn = null;
          
          for (var i = 0; i < buttons.length; i++) {
            var btn = buttons[i];
            if (btn.innerText && btn.innerText.trim() === '추천') {
              var rect = btn.getBoundingClientRect();
              if (rect.top > window.innerHeight / 2) {
                recommendBtn = btn.closest('li, a, div[class*="item"], div[class*="btn"]') || btn;
                break;
              }
            }
          }
          if (!recommendBtn) return;

          var listenBtn = recommendBtn.cloneNode(true);
          listenBtn.id = 'np-tts-injected-btn';
          listenBtn.style.cursor = 'pointer';
          listenBtn.innerHTML = listenBtn.innerHTML.replace('추천', '듣기');

          var iconEl = listenBtn.querySelector('svg, i, img, span[class*="icon"]');
          if (iconEl) {
            var iconClass = iconEl.getAttribute('class') || '';
            iconEl.outerHTML = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="' + iconClass + '"><path d="M3 18v-6a9 9 0 0 1 18 0v6"></path><path d="M21 19a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2h3zM3 19a2 2 0 0 0 2 2h1a2 2 0 0 0 2-2v-3a2 2 0 0 0-2-2H3z"></path></svg>';
          }

          if (listenBtn.tagName === 'A') listenBtn.removeAttribute('href');
          var children = listenBtn.querySelectorAll('*');
          for(var j=0; j<children.length; j++) {
            if (children[j].removeAttribute) {
              children[j].removeAttribute('onclick');
              children[j].removeAttribute('href');
            }
          }

          listenBtn.addEventListener('click', function(e) {
            e.preventDefault();
            e.stopPropagation();
            if (window._NPTTS && typeof window._NPTTS.open === "function") window._NPTTS.open();
          }, true);

          var parentUl = recommendBtn.parentNode;
          parentUl.insertBefore(listenBtn, recommendBtn);
          
          parentUl.style.display = 'flex';
          parentUl.style.justifyContent = 'space-between';
          parentUl.style.width = '100%';
          parentUl.style.padding = '0';
          
          var allItems = parentUl.children;
          for(var k=0; k<allItems.length; k++) {
              allItems[k].style.flex = '1 1 0';
              allItems[k].style.width = 'auto';
              allItems[k].style.display = 'flex';
              allItems[k].style.flexDirection = 'column';
              allItems[k].style.alignItems = 'center';
              allItems[k].style.justifyContent = 'center';
          }
        }, 1000);
      })();
    """.trimIndent()
    view.evaluateJavascript(js, null)
  }

  @SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
  private fun setupPreloadWebView() {
    preloadWebView = WebView(this)
    preloadWebView.alpha = 0f
    preloadWebView.isClickable = false
    preloadWebView.isFocusable = false
    preloadWebView.settings.javaScriptEnabled = true
    preloadWebView.settings.domStorageEnabled = true
    preloadWebView.settings.loadsImagesAutomatically = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      preloadWebView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
    }
    preloadWebView.webChromeClient = WebChromeClient()
    preloadWebView.webViewClient = object : WebViewClient() {
      override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (url?.contains(VIEWER_URL_PART) != true) {
          ttsController.clearPreloadedChapter()
        }
      }

      override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (url?.contains(VIEWER_URL_PART) != true) return
        installTtsScript(view)
        ttsController.onPreloadPageFinished(url)
      }
    }
    findViewById<android.widget.FrameLayout>(R.id.main).addView(
      preloadWebView,
      android.widget.FrameLayout.LayoutParams(1, 1)
    )
  }

  private fun setupListeners() {
    swipeRefresh.setOnRefreshListener { webView.reload() }
    webView.setOnLongClickListener { showMainMenu(); true }
  }

  private fun installTtsScript(view: WebView) { view.evaluateJavascript(ttsScript, null) }

  private fun showMainMenu() {
    val menuItems = listOf(
      MainMenuItem.QuickActions(listOf(MainMenuAction.Back, MainMenuAction.Forward, MainMenuAction.Refresh, MainMenuAction.Bookmarks, MainMenuAction.StartPage)),
      MainMenuItem.Divider,
      MainMenuItem.Action(R.drawable.ic_settings_24, getString(R.string.menu_settings), MainMenuAction.Settings),
      MainMenuItem.Action(R.drawable.ic_settings_24, "TTS 정규식 설정", MainMenuAction.TtsRegexSettings)
    )
    var dialog: AlertDialog? = null
    AlertDialog.Builder(this)
      .setAdapter(MainMenuAdapter(menuItems) { action -> dialog?.dismiss(); handleMainMenuAction(action) }) 
      { _, which ->
        when (menuItems[which]) {
          is MainMenuItem.Action -> { dialog?.dismiss(); handleMainMenuAction((menuItems[which] as MainMenuItem.Action).action) }
          is MainMenuItem.QuickActions, MainMenuItem.Divider -> Unit
        }
      }.show().also { dialog = it }
  }

  private fun handleMainMenuAction(action: MainMenuAction) {
    when (action) {
      MainMenuAction.Back -> goBackInWebView()
      MainMenuAction.Forward -> goForwardInWebView()
      MainMenuAction.Refresh -> webView.reload()
      MainMenuAction.Bookmarks -> openBookmarks()
      MainMenuAction.StartPage -> openStartPage()
      MainMenuAction.Settings -> startActivity(Intent(this, SettingsActivity::class.java))
      MainMenuAction.TtsRegexSettings -> startActivity(Intent(this, TtsRegexSettingsActivity::class.java))
    }
  }

  private fun goBackInWebView() {
    if (!webView.canGoBack()) return
    if (webView.url?.contains(VIEWER_URL_PART) == true) restoringFromViewer = true
    saveScrollPosition()
    webView.goBack()
  }

  private fun goForwardInWebView() {
    if (!webView.canGoForward()) return
    saveScrollPosition()
    webView.goForward()
  }

  private fun openBookmarks() {
    bookmarkLauncher.launch(Intent(this, BookmarksActivity::class.java).putExtra(BookmarksActivity.EXTRA_CURRENT_TITLE, webView.title.orEmpty()).putExtra(BookmarksActivity.EXTRA_CURRENT_URL, webView.url.orEmpty()))
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
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
      when (val item = getItem(position)) {
        is MainMenuItem.Action -> {
          val view = convertView?.takeIf { it.id != View.NO_ID } ?: LayoutInflater.from(parent.context).inflate(R.layout.item_icon_menu, parent, false)
          (view as TextView).bindIconMenuItem(item.iconRes, item.title)
          view
        }
        is MainMenuItem.QuickActions -> createQuickActionRow(item.actions, parent)
        MainMenuItem.Divider -> {
          View(parent.context).apply {
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(parent.context, android.R.color.darker_gray))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, parent.resources.getDimensionPixelSize(R.dimen.main_long_press_menu_divider_height))
          }
        }
      }

    private fun createQuickActionRow(actions: List<MainMenuAction>, parent: ViewGroup): LinearLayout =
      LinearLayout(parent.context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, parent.resources.getDimensionPixelSize(R.dimen.icon_menu_item_height))
        actions.forEach { action -> addView(createQuickActionButton(action), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)) }
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
    data class QuickActions(val actions: List<MainMenuAction>) : MainMenuItem
    data class Action(val iconRes: Int, val title: String, val action: MainMenuAction) : MainMenuItem
    data object Divider : MainMenuItem
  }

  private enum class MainMenuAction(val iconRes: Int, val titleRes: Int) {
    Back(R.drawable.ic_arrow_back_24, R.string.menu_back),
    Forward(R.drawable.ic_arrow_forward_24, R.string.menu_forward),
    Refresh(R.drawable.ic_refresh_24, R.string.menu_refresh),
    Bookmarks(R.drawable.ic_star_24, R.string.menu_bookmarks),
    StartPage(R.drawable.ic_home_24, R.string.menu_start_page),
    Settings(R.drawable.ic_settings_24, R.string.menu_settings),
    TtsRegexSettings(R.drawable.ic_settings_24, R.string.menu_settings),
  }

  private fun MainMenuAction.isAvailable(): Boolean =
    when (this) {
      MainMenuAction.Back -> webView.canGoBack()
      MainMenuAction.Forward -> webView.canGoForward()
      MainMenuAction.Bookmarks, MainMenuAction.Refresh, MainMenuAction.StartPage, MainMenuAction.Settings, MainMenuAction.TtsRegexSettings -> true
    }

  private fun TextView.bindIconMenuItem(iconRes: Int, title: String) {
    text = title
    val icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, iconRes)
    val iconSize = resources.getDimensionPixelSize(R.dimen.icon_menu_icon_size)
    icon?.setBounds(0, 0, iconSize, iconSize)
    setCompoundDrawablesRelative(icon, null, null, null)
  }

  private fun setupUpdate() {
    UpdateNotifier.initChannel(this)
    UpdateChecker.processAppUpdate(this)
    val autoCheck = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("auto_check_update", true)
    if (autoCheck) { lifecycleScope.launch { UpdateChecker.checkAndNotify(applicationContext) } }
  }

  private fun setupFilters() {
    lifecycleScope.launch {
      runCatching {
        withContext(Dispatchers.IO) {
          if (FilterPreferences.shouldRefresh(applicationContext)) filterRuntime.updateSubscriptions()
          filterRuntime.refreshEngine()
        }
      }
    }
  }

  private fun setupBackHandler() {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (webView.canGoBack()) {
          if (webView.url?.contains(VIEWER_URL_PART) == true) restoringFromViewer = true
          saveScrollPosition()
          webView.goBack()
          return
        }
        val now = System.currentTimeMillis()
        if (now - lastBackPress < 2000) { finish() } else {
          lastBackPress = now
          Toast.makeText(this@MainActivity, R.string.press_twice_to_exit, Toast.LENGTH_SHORT).show()
        }
      }
    })
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && ttsController.isOverlayVisible()) {
        return super.onKeyDown(keyCode, event)
    }

    val url = webView.url ?: ""
    if (url.contains(VIEWER_URL_PART)) {
      val prefs = PreferenceManager.getDefaultSharedPreferences(this)
      if (prefs.getString("volume_behavior", "move_page") == "move_page") {
        val upPrev = prefs.getString("volume_direction", "up_prev") == "up_prev"
        val selector = when (keyCode) {
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
    keepWebViewTimersRunning()
    if (::preloadWebView.isInitialized) preloadWebView.onResume()
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    swipeRefresh.triggerFraction = prefs.getString("swipe_fraction", null)?.toFloatOrNull() ?: TopSwipeRefreshLayout.DEFAULT_TRIGGER_FRACTION
  }

  override fun onPause() {
    // Intentionally do not call webView.onPause() here. Keeping the WebView
    // resumed prevents screen-off binge playback from suspending its page
    // timers/JavaScript while the app continues to hold the playback WakeLock.
    super.onPause()
  }

  private fun keepWebViewTimersRunning() {
    // WebView.onResume() is an instance API and is sufficient here because
    // we never pause this WebView during the binge lifecycle.
    webView.onResume()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    webView.saveState(outState)
  }

  override fun onDestroy() {
    unregisterReceiver(ttsControlReceiver)
    ttsController.destroy()
    super.onDestroy()
  }

  private fun saveScrollPosition() {
    val url = webView.url ?: return
    if (url.contains(VIEWER_URL_PART)) return
    if (scrollPositions.size >= MAX_SCROLL_CACHE && !scrollPositions.containsKey(url)) { scrollPositions.remove(scrollPositions.keys.first()) }
    scrollPositions[url] = webView.scrollY
  }

  private fun injectWebViewScript(view: WebView) {
    documentStartScripts.forEach { script -> view.evaluateJavascript(script, null) }
  }

  private fun refreshCosmeticFilters(view: WebView) {
    view.evaluateJavascript("window.__NovelRegExRefreshCosmetic&&window.__NovelRegExRefreshCosmetic();", null)
  }

  inner class NovelTtsController : UtteranceProgressListener(), TextToSpeech.OnInitListener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var startPending = false
    
    private val sentences = mutableListOf<TtsSentence>()
    private var totalLines = 0
    private var currentSentenceIndex = -1
    private var active = false
    private var paused = false
    private var speed = 1.0f
    private var bingeMode = true
    private var waitingForNextChapter = false
    private var generation = 0
    private var retryCount = 0

    private var overlayRoot: View? = null
    private var titleText: TextView? = null
    private var counterText: TextView? = null
    private var playPauseButton: TextView? = null
    private var modeText: TextView? = null
    private var speedText: TextView? = null
    private var progressContainer: android.widget.FrameLayout? = null
    private var progressView: TtsProgressView? = null

    private var mediaSession: MediaSession? = null

    private var nextChapterRetryCount = 0
    private var nextChapterNavigationStarted = false
    private var pendingChapterFromUrl: String? = null
    private var preloadedChapterUrl: String? = null
    private var preloadedChapterJson: String? = null
    private var preloadCollectRetry = 0
    private var preloadInFlight = false

    @Suppress("DEPRECATION")
    private val wifiLock: WifiManager.WifiLock =
      (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "${packageName}:NovelRegEx-Binge"
      ).apply {
        setReferenceCounted(false)
      }
    private val playbackWakeLock: PowerManager.WakeLock =
      (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "${packageName}:NovelTTS-Playback"
      ).apply {
        setReferenceCounted(false)
      }

    private fun syncPlaybackWakeLock() {
      val shouldHold = active || waitingForNextChapter
      if (shouldHold) {
        if (!playbackWakeLock.isHeld) playbackWakeLock.acquire()
        try { if (!wifiLock.isHeld) wifiLock.acquire() } catch (_: Throwable) {}
      } else {
        if (playbackWakeLock.isHeld) playbackWakeLock.release()
        try { if (wifiLock.isHeld) wifiLock.release() } catch (_: Throwable) {}
      }
    }

    init { 
      textToSpeech = TextToSpeech(this@MainActivity, this) 
      
      mediaSession = MediaSession(this@MainActivity, "NovelTtsSession").apply {
        setCallback(object : MediaSession.Callback() {
          override fun onPlay() { mainHandler.post { togglePlayPause() } }
          override fun onPause() { mainHandler.post { togglePlayPause() } }
          override fun onSkipToNext() { mainHandler.post { next() } }
          override fun onSkipToPrevious() { mainHandler.post { previous() } }
        })
        isActive = true
      }
    }

    override fun onInit(status: Int) {
      mainHandler.post {
        if (status != TextToSpeech.SUCCESS) {
          ttsReady = false
          Toast.makeText(this@MainActivity, "기본 TTS 엔진을 초기화하지 못했습니다.", Toast.LENGTH_LONG).show()
          return@post
        }
        val result = textToSpeech?.setLanguage(Locale.KOREAN) ?: TextToSpeech.ERROR
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
          val fallback = textToSpeech?.setLanguage(Locale.KOREA) ?: TextToSpeech.ERROR
          if (fallback == TextToSpeech.LANG_MISSING_DATA || fallback == TextToSpeech.LANG_NOT_SUPPORTED) {
            ttsReady = false
            Toast.makeText(this@MainActivity, "한국어 TTS 데이터가 설치되어 있지 않습니다.", Toast.LENGTH_LONG).show()
            return@post
          }
        }
        textToSpeech?.setOnUtteranceProgressListener(this)
        ttsReady = true
        if (startPending) { startPending = false; openAndStart() }
      }
    }

    fun markPageStarted(url: String?) {
      if (!waitingForNextChapter) return
      if (!url.isNullOrBlank() && url != pendingChapterFromUrl) {
        nextChapterNavigationStarted = true
      }
    }

    fun onViewerPageReady() {
      if (!currentPageIsViewer) return
      if (waitingForNextChapter) {
        if (tryStartFromPreloadedChapter()) return
        retryCount = 0
        nextChapterNavigationStarted = false
        mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true) }, 150L)
        return
      }
      if (!active && sentences.isEmpty()) return
    }

    private fun clearPreloadState() {
      preloadedChapterUrl = null
      preloadedChapterJson = null
      preloadCollectRetry = 0
      preloadInFlight = false
    }

    fun clearPreloadedChapter() = clearPreloadState()

    fun prepareNextChapterPreload() {
      if (!bingeMode || !currentPageIsViewer || preloadInFlight || preloadedChapterJson != null) return
      val script = """
        (function(){
          var els = document.querySelectorAll('a,button,div,span,p,li');
          for(var i=0;i<els.length;i++){
            var raw = els[i].innerText || els[i].textContent || '';
            var txt = raw.replace(/\s/g,'');
            if(txt === '다음화보기' || txt === '다음화'){
              var a = els[i].closest && els[i].closest('a[href]');
              var h = a && a.href ? a.href : '';
              if(h && h.indexOf('javascript:') !== 0) return h;
            }
          }
          var f = document.querySelector('#novel_drawing_right, #next_epi_btn_bottom, .menu-next-item, .btn-next-episode');
          var a2 = f && f.closest ? f.closest('a[href]') : null;
          var h2 = a2 && a2.href ? a2.href : '';
          return h2;
        })();
      """.trimIndent()
      webView.evaluateJavascript(script) { result ->
        mainHandler.post {
          val url = runCatching { JSONTokener(result ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
          if (url.isBlank() || !url.contains(VIEWER_URL_PART)) return@post
          if (url == webView.url) return@post
          preloadInFlight = true
          preloadedChapterUrl = url
          preloadedChapterJson = null
          preloadCollectRetry = 0
          preloadWebView.loadUrl(url)
        }
      }
    }

    fun onPreloadPageFinished(url: String) {
      if (!preloadInFlight || url != preloadedChapterUrl) return
      preloadCollectRetry = 0
      mainHandler.postDelayed({ tryCollectPreloadedChapter() }, 200L)
    }

    private fun tryCollectPreloadedChapter() {
      if (!preloadInFlight || preloadedChapterUrl.isNullOrBlank()) return
      preloadWebView.evaluateJavascript("window.__npTts&&window.__npTts.collect()") { result ->
        mainHandler.post {
          val decoded = runCatching { JSONTokener(result ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
          if (decoded.isNotBlank() && decoded != "null") {
            val root = runCatching { org.json.JSONObject(decoded) }.getOrNull()
            val count = root?.optJSONArray("sentences")?.length() ?: 0
            if (count > 0) {
              preloadedChapterJson = decoded
              preloadInFlight = false
              if (waitingForNextChapter) {
                mainHandler.post { tryStartFromPreloadedChapter() }
              }
              return@post
            }
          }
          if (preloadCollectRetry < 80) {
            preloadCollectRetry++
            mainHandler.postDelayed({ tryCollectPreloadedChapter() }, 250L)
          } else {
            preloadInFlight = false
          }
        }
      }
    }

    private fun tryStartFromPreloadedChapter(): Boolean {
      val url = preloadedChapterUrl
      val json = preloadedChapterJson
      if (!waitingForNextChapter || url.isNullOrBlank() || json.isNullOrBlank()) return false
      if (url == pendingChapterFromUrl) return false
      waitingForNextChapter = false
      nextChapterNavigationStarted = false
      pendingChapterFromUrl = null
      preloadedChapterUrl = null
      preloadedChapterJson = null
      preloadInFlight = false
      active = true
      paused = false
      syncPlaybackWakeLock()
      webView.loadUrl(url)
      applyCollectedJsonAndStart(json, true)
      mainHandler.postDelayed({ prepareNextChapterPreload() }, 1500L)
      return true
    }

    private fun applyCollectedJsonAndStart(decoded: String, forNextChapter: Boolean) {
      try {
        val root = org.json.JSONObject(decoded)
        val title = root.optString("title").trim()
        val episode = root.optString("episode").trim()
        titleText?.text = buildString {
          if (episode.isNotEmpty()) append(episode)
          if (episode.isNotEmpty() && title.isNotEmpty()) append(' ')
          if (title.isNotEmpty()) append(title)
        }.ifBlank { webView.title.orEmpty() }
        totalLines = root.optInt("lineCount", 0)
        sentences.clear()
        val list = root.optJSONArray("sentences") ?: JSONArray()
        for (index in 0 until list.length()) {
          val item = list.optJSONObject(index) ?: continue
          val text = item.optString("text").trim()
          if (text.isNotEmpty()) sentences.add(TtsSentence(line = item.optInt("line", 0), text = text))
        }
        if (sentences.isEmpty()) return
        currentSentenceIndex = -1
        active = true
        paused = false
        waitingForNextChapter = false
        generation++
        updatePlayButton()
        speakNext()
      } catch (_: Throwable) {}
    }

    fun openAndStart() {
      if (!currentPageIsViewer) return
      ensureOverlay()
      showOverlay(true)
      if (!ttsReady) {
        startPending = true
        Toast.makeText(this@MainActivity, "TTS 엔진을 준비하는 중입니다.", Toast.LENGTH_SHORT).show()
        return
      }
      startPending = false
      retryCount = 0
      collectAndStart(0, forNextChapter = false)
    }

    private fun collectAndStart(retry: Int, forNextChapter: Boolean) {
      if (!currentPageIsViewer) return
      webView.evaluateJavascript("window.__npTts&&window.__npTts.collect()") { result ->
        mainHandler.post {
          if (result.isNullOrBlank() || result == "null" || result == "\"\"") {
            if (retry < 60) {
              mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter) }, 250L)
            } else if (forNextChapter && waitingForNextChapter) {
              webView.reload()
              mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true) }, 800L)
            } else {
              Toast.makeText(this@MainActivity, "회차 본문을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
            }
            return@post
          }
          try {
            val decoded = JSONTokener(result).nextValue() as? String
            if (decoded.isNullOrBlank()) {
              if (retry < 60) {
                mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter) }, 250L)
              } else if (forNextChapter && waitingForNextChapter) {
                webView.reload()
                mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true) }, 800L)
              }
              return@post
            }
            val root = org.json.JSONObject(decoded)
            val title = root.optString("title").trim()
            val episode = root.optString("episode").trim()

            titleText?.text = buildString {
              if (episode.isNotEmpty()) append(episode)
              if (episode.isNotEmpty() && title.isNotEmpty()) append(' ')
              if (title.isNotEmpty()) append(title)
            }.ifBlank { webView.title.orEmpty() }

            totalLines = root.optInt("lineCount", 0)
            sentences.clear()
            val list = root.optJSONArray("sentences") ?: JSONArray()
            for (index in 0 until list.length()) {
              val item = list.optJSONObject(index) ?: continue
              val text = item.optString("text").trim()
              if (text.isNotEmpty()) {
                sentences.add(TtsSentence(line = item.optInt("line", 0), text = text))
              }
            }

            if (sentences.isEmpty()) {
              if (retry < 60) {
                mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter) }, 250L)
              } else if (forNextChapter && waitingForNextChapter) {
                webView.reload()
                mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true) }, 800L)
              } else {
                Toast.makeText(this@MainActivity, "읽을 수 있는 본문을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
              }
              return@post
            }

            currentSentenceIndex = -1
            active = true
            paused = false
            if (forNextChapter) {
              waitingForNextChapter = false
              nextChapterRetryCount = 0
              nextChapterNavigationStarted = false
              pendingChapterFromUrl = null
            } else {
              waitingForNextChapter = false
            }
            syncPlaybackWakeLock()
            generation++
            updatePlayButton()
            speakNext()
            mainHandler.postDelayed({ prepareNextChapterPreload() }, 1200L)
          } catch (error: Exception) {
            if (retry < 60) {
              mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter) }, 250L)
            } else if (forNextChapter && waitingForNextChapter) {
              webView.reload()
              mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true) }, 800L)
            } else {
              Toast.makeText(this@MainActivity, "TTS 본문 분석에 실패했습니다: ${error.message.orEmpty()}", Toast.LENGTH_LONG).show()
            }
          }
        }
      }
    }

    fun speakNext() {
      if (!active || paused) return
      currentSentenceIndex++
      if (currentSentenceIndex >= sentences.size) {
        finishEpisode()
        return
      }
      speakCurrent()
    }

    /**
     * 화면에 표시되는 원문은 건드리지 않고, 실제 TTS에 넘길 문자열만 정리한다.
     * 사용자 정규식 -> 숫자/기호 발음 정규화 순서로 적용한다.
     */
    private fun prepareTtsText(original: String): String {
      var result = original.trim()
      if (result.isEmpty()) return result

      // 1) 사용자 정규식 적용
      for (rule in TtsRegexStore.load(this@MainActivity)) {
        if (!rule.enabled || rule.pattern.isBlank()) continue

        result = try {
          val flags = if (rule.ignoreCase) {
            java.util.regex.Pattern.CASE_INSENSITIVE or java.util.regex.Pattern.UNICODE_CASE
          } else {
            java.util.regex.Pattern.UNICODE_CASE
          }

          val pattern = if (rule.isRegex) {
            java.util.regex.Pattern.compile(rule.pattern, flags)
          } else {
            java.util.regex.Pattern.compile(
              java.util.regex.Pattern.quote(rule.pattern),
              flags
            )
          }

          val replacement = if (rule.isRegex) {
            rule.replacement
          } else {
            java.util.regex.Matcher.quoteReplacement(rule.replacement)
          }
          pattern.matcher(result).replaceAll(replacement)
        } catch (_: Exception) {
          // 잘못된 사용자 규칙 하나 때문에 TTS 전체가 멈추지 않게 무시한다.
          result
        }
      }

      // 2) 사이트 UI 잔재/불필요한 공백 정리
      result = result
        .replace(Regex("[\\u200B\\u00A0]"), "")
        .replace(Regex("[\\t\\r\\n]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()

      // 3) 분수/슬래시는 한국어 TTS가 안정적으로 읽도록 명시적으로 변환한다.
      //    예: 3.1/100 -> 3.1 나누기 100
      result = result.replace(
        Regex("([0-9]+(?:[.,][0-9]+)?)\\s*/\\s*([0-9]+(?:[.,][0-9]+)?)"),
        "\$1 나누기 \$2"
      )

      // 4) 소수점은 '점'으로 명시한다. 3.1 -> 3 점 1
      result = result.replace(
        Regex("(?<![0-9])([0-9]+)\\.([0-9]+)(?![0-9])"),
        "\$1 점 \$2"
      )

      // 5) 천 단위 쉼표 제거: 1,000 -> 1000
      result = result.replace(
        Regex("(?<=\\d),(?=\\d{3}(?:\\D|$))"),
        ""
      )

      // 6) 기존 발음 치환
      result = result
        .replace(Regex("([0-9.]+)\\s*%"), "${'$'}1 퍼센트")
        .replace(Regex("([0-9.]+)\\s*\\$"), "${'$'}1 달러")
        .replace(Regex("([0-9.]+)\\s*¥"), "${'$'}1 엔")
        .replace(Regex("([0-9.]+)\\s*€"), "${'$'}1 유로")
        .replace(Regex("([0-9.]+)\\s*kg", RegexOption.IGNORE_CASE), "${'$'}1 킬로그램")
        .replace(Regex("([0-9.]+)\\s*km", RegexOption.IGNORE_CASE), "${'$'}1 킬로미터")
        .replace(Regex("([0-9.]+)\\s*cm", RegexOption.IGNORE_CASE), "${'$'}1 센티미터")
        .replace(Regex("([0-9.]+)\\s*mm", RegexOption.IGNORE_CASE), "${'$'}1 밀리미터")
        .replace(Regex("([0-9.]+)\\s*m\\b", RegexOption.IGNORE_CASE), "${'$'}1 미터")
        .replace(Regex("([0-9.]+)\\s*g\\b", RegexOption.IGNORE_CASE), "${'$'}1 그램")
        .replace(Regex("([0-9.]+)\\s*ml\\b", RegexOption.IGNORE_CASE), "${'$'}1 밀리리터")
        .replace(Regex("([0-9.]+)\\s*l\\b", RegexOption.IGNORE_CASE), "${'$'}1 리터")

      return result
        .replace(Regex("\\s{2,}"), " ")
        .trim()
    }

    private fun speakCurrent() {
      if (!active || paused || currentSentenceIndex !in sentences.indices) return
      syncPlaybackWakeLock()
      val sentence = sentences[currentSentenceIndex]
      val localGeneration = generation
      val utteranceId = "np_tts_${localGeneration}_$currentSentenceIndex"
      
      mainHandler.post {
        updateProgress(sentence.line)
        highlightCurrentSentence()
      }

      val cleanText = prepareTtsText(sentence.text)

      val hasLetters = Regex("[가-힣a-zA-Z0-9]").containsMatchIn(cleanText)

      // 숫자-only 문장(예: 3.)을 하드코딩으로 무조건 묵음 처리하지 않는다.
      // 사용자가 TTS 정규식 설정에서 해당 문장을 빈 문자열로 치환한 경우에만 묵음 처리된다.
      if (cleanText.isBlank() || !hasLetters) {
          mainHandler.post { speakNext() }
          return
      }

      val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
      textToSpeech?.setSpeechRate(speed)
      
      val result = textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId) ?: TextToSpeech.ERROR
      if (result == TextToSpeech.ERROR) {
        mainHandler.post { 
          Toast.makeText(this@MainActivity, "TTS 재생 요청에 실패했습니다.", Toast.LENGTH_SHORT).show()
          speakNext() 
        }
      }
    }

    override fun onStart(utteranceId: String) {
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      mainHandler.post {
        if (!active || paused || parsed.second !in sentences.indices) return@post
        currentSentenceIndex = parsed.second
        updateProgress(sentences[parsed.second].line)
        highlightCurrentSentence()
      }
    }

    override fun onDone(utteranceId: String) {
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      mainHandler.post {
        if (!active || paused || parsed.second != currentSentenceIndex) return@post
        speakNext()
      }
    }

    @Deprecated("Deprecated by Android API")
    override fun onError(utteranceId: String) { onError(utteranceId, TextToSpeech.ERROR) }

    override fun onError(utteranceId: String, errorCode: Int) {
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      mainHandler.post {
        if (!active || paused) return@post
        speakNext() 
      }
    }

    override fun onStop(utteranceId: String, interrupted: Boolean) {}

    private fun parseUtteranceId(id: String): Pair<Int, Int>? {
      val parts = id.split('_')
      if (parts.size != 4) return null
      return try { parts[2].toInt() to parts[3].toInt() } catch (_: NumberFormatException) { null }
    }

    private fun highlightCurrentSentence() {
      val index = currentSentenceIndex
      if (index !in sentences.indices) return
      webView.evaluateJavascript("window.__npTts&&window.__npTts.highlight($index)", null)
    }

    private fun updateProgress(lineIndex: Int) {
      counterText?.text = if (totalLines > 0) "${lineIndex + 1} / $totalLines" else "0 / 0"
      progressView?.progress = if (totalLines <= 1) 0f else lineIndex.toFloat().coerceIn(0f, (totalLines - 1).toFloat()) / (totalLines - 1)
      webView.evaluateJavascript("window.__npTts&&window.__npTts.scrollToLine($lineIndex)", null)
    }

    private fun finishEpisode() {
      if (!active) return
      if (bingeMode) {
        active = false
        waitingForNextChapter = true
        nextChapterRetryCount = 0
        nextChapterNavigationStarted = false
        pendingChapterFromUrl = webView.url
        textToSpeech?.stop()
        syncPlaybackWakeLock()
        updatePlayButton()
        if (tryStartFromPreloadedChapter()) return
        requestNextChapter(0)
      } else {
        active = false
        paused = false
        waitingForNextChapter = false
        syncPlaybackWakeLock()
        updatePlayButton()
        progressView?.progress = 1f
      }
    }

    private fun requestNextChapter(attempt: Int) {
      if (!waitingForNextChapter || !currentPageIsViewer || nextChapterNavigationStarted) return
      nextChapterRetryCount = attempt
      val currentUrl = pendingChapterFromUrl ?: webView.url
      val nextScript = """
        (function() {
          var els = document.querySelectorAll('a,button,div,span,p,li');
          for (var i = 0; i < els.length; i++) {
            var raw = els[i].innerText || els[i].textContent || '';
            var txt = raw.replace(/\s/g, '');
            if (txt === '다음화보기' || txt === '다음화') {
              var c = els[i].closest('a,button,li,div[class*="btn"]') || els[i];
              var a = c.closest ? c.closest('a[href]') : null;
              var href = a && a.href ? a.href : '';
              if (href && href.indexOf('javascript:') !== 0) return href;
              try { c.click(); return 'CLICKED'; } catch (e) { return ''; }
            }
          }
          var fallback = document.querySelector('#novel_drawing_right, .btn-next-episode');
          if (fallback) {
            var a2 = fallback.closest ? fallback.closest('a[href]') : null;
            var href2 = a2 && a2.href ? a2.href : '';
            if (href2 && href2.indexOf('javascript:') !== 0) return href2;
            try { fallback.click(); return 'CLICKED'; } catch (e) {}
          }
          return '';
        })();
      """.trimIndent()

      webView.evaluateJavascript(nextScript) { result ->
        mainHandler.post {
          if (!waitingForNextChapter) return@post
          val value = runCatching { JSONTokener(result ?: "\"\"").nextValue() as? String }.getOrNull().orEmpty()
          if (value.isNotBlank() && value != "CLICKED") {
            if (value != currentUrl) {
              nextChapterNavigationStarted = true
              webView.loadUrl(value)
              return@post
            }
          } else if (value == "CLICKED") {
            nextChapterNavigationStarted = true
            return@post
          }

          if (attempt < 40) {
            mainHandler.postDelayed({
              if (!waitingForNextChapter || nextChapterNavigationStarted) return@postDelayed
              requestNextChapter(attempt + 1)
            }, 300L)
          } else {
            mainHandler.postDelayed({
              if (!waitingForNextChapter || nextChapterNavigationStarted) return@postDelayed
              webView.reload()
              requestNextChapter(0)
            }, 1000L)
          }
        }
      }
    }

    fun previous() {
      if (sentences.isEmpty()) return
      currentSentenceIndex = (currentSentenceIndex - 2).coerceAtLeast(-1)
      generation++
      textToSpeech?.stop()
      active = true
      paused = false
      syncPlaybackWakeLock()
      speakNext()
    }

    fun next() {
      if (sentences.isEmpty()) return
      generation++
      textToSpeech?.stop()
      syncPlaybackWakeLock()
      speakNext()
    }

    fun togglePlayPause() {
      // 완전히 정지된 상태: 현재 선택 위치에서 재생
      if (!active && !paused) {
        if (sentences.isEmpty()) {
          openAndStart()
        } else {
          active = true
          paused = false
          syncPlaybackWakeLock()
          generation++
          if (currentSentenceIndex in sentences.indices) {
            speakCurrent()
          } else {
            speakNext()
          }
          updatePlayButton()
        }
        return
      }

      // 일시정지 상태: 현재 선택 위치에서 재생 재개
      if (paused) {
        paused = false
        active = true
        syncPlaybackWakeLock()
        generation++
        if (currentSentenceIndex in sentences.indices) {
          speakCurrent()
        } else {
          speakNext()
        }
        updatePlayButton()
        return
      }

      // 재생 중 -> 일시정지
      paused = true
      active = false
      syncPlaybackWakeLock()
      generation++
      textToSpeech?.stop()
      updatePlayButton()
    }

    private fun cycleSpeed() {
      val values = floatArrayOf(0.8f, 1.0f, 1.2f, 1.5f, 2.0f)
      val currentIndex = values.indexOfFirst { abs(it - speed) < 0.001f }.takeIf { it >= 0 } ?: 1
      speed = values[(currentIndex + 1) % values.size]
      textToSpeech?.setSpeechRate(speed)
      speedText?.text = String.format(Locale.US, "%.1fx ⌄", speed)
      
      if (active && !paused) {
          updateMediaNotification(true)
      }
    }

    fun isOverlayVisible(): Boolean {
      return overlayRoot?.visibility == View.VISIBLE
    }

    private fun ensureOverlay() {
      if (overlayRoot != null) return

      overlayRoot = findViewById(R.id.tts_overlay_root)
      titleText = findViewById(R.id.tts_title_text)
      counterText = findViewById(R.id.tts_counter_text)
      playPauseButton = findViewById(R.id.tts_play_pause_button)
      modeText = findViewById(R.id.tts_mode_text)
      speedText = findViewById(R.id.tts_speed_text)
      progressContainer = findViewById(R.id.tts_progress_container)

      val closeButton: TextView = findViewById(R.id.tts_close_button)
      val previousButton: TextView = findViewById(R.id.tts_previous_button)
      val nextButton: TextView = findViewById(R.id.tts_next_button)

      closeButton.setOnClickListener { close() }
      previousButton.setOnClickListener { previous() }
      playPauseButton?.setOnClickListener { togglePlayPause() }
      nextButton.setOnClickListener { next() }

      modeText?.setOnClickListener {
        bingeMode = !bingeMode
        (it as TextView).text = if (bingeMode) "정주행" else "한 화"
      }

      speedText?.setOnClickListener { cycleSpeed() }

      if (progressView == null) {
        progressView = TtsProgressView(this@MainActivity).apply { setOnSeekListener { fraction -> seekByFraction(fraction) } }
        progressContainer?.addView(progressView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
      }
    }

    private fun showOverlay(show: Boolean) {
      overlayRoot?.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun seekByFraction(fraction: Float) {
      if (sentences.isEmpty()) return

      val targetIndex = (
        fraction.coerceIn(0f, 1f) * (sentences.size - 1)
      ).roundToInt().coerceIn(0, sentences.lastIndex)

      // 탐색 전 재생 상태를 보존한다.
      val wasPlaying = active && !paused

      generation++
      textToSpeech?.stop()

      if (wasPlaying) {
        // 재생 중 탐색: 선택한 위치에서 즉시 계속 재생
        currentSentenceIndex = targetIndex - 1
        active = true
        paused = false
        speakNext()
      } else {
        // 정지/일시정지 상태 탐색: 위치만 옮기고 자동 재생하지 않음
        currentSentenceIndex = targetIndex
        active = false
        paused = true

        val sentence = sentences[targetIndex]
        updateProgress(sentence.line)
        highlightCurrentSentence()
        updatePlayButton()
      }
    }

    private fun updatePlayButton() {
      val isPlaying = active && !paused
      playPauseButton?.text = if (isPlaying) "Ⅱ" else "▶"
      updateMediaNotification(isPlaying)
    }

    private fun updateMediaNotification(isPlaying: Boolean) {
      val state = if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
      mediaSession?.setPlaybackState(
          PlaybackState.Builder()
              .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed)
              .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
              .build()
      )

      val metaData = MediaMetadata.Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, titleText?.text?.toString() ?: "노벨피아")
          .putString(MediaMetadata.METADATA_KEY_ARTIST, "노벨피아 TTS")
          .build()
      mediaSession?.setMetadata(metaData)

      val manager = getSystemService(NotificationManager::class.java)
      val channelId = "tts_media_channel_v5"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          val channel = NotificationChannel(channelId, "노벨피아 TTS", NotificationManager.IMPORTANCE_HIGH)
          channel.setSound(null, null)
          channel.enableVibration(false)
          channel.setShowBadge(false)
          manager.createNotificationChannel(channel)
      }

      val playPauseIntent = Intent("ACTION_TOGGLE_PLAY").setPackage(packageName)
      val playPausePI = PendingIntent.getBroadcast(this@MainActivity, 1, playPauseIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      
      val nextIntent = Intent("ACTION_NEXT").setPackage(packageName)
      val nextPI = PendingIntent.getBroadcast(this@MainActivity, 2, nextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      
      val prevIntent = Intent("ACTION_PREV").setPackage(packageName)
      val prevPI = PendingIntent.getBroadcast(this@MainActivity, 3, prevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

      val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
      val playPauseTitle = if (isPlaying) "Pause" else "Play"

      val contentIntent = Intent(this@MainActivity, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
      val pendingContentIntent = PendingIntent.getActivity(this@MainActivity, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE)

      val builder = Notification.Builder(this@MainActivity, channelId)
          .setSmallIcon(R.drawable.ic_notification_NovelRegEx)
          .setContentTitle(titleText?.text?.toString() ?: "노벨피아 TTS")
          .setContentText(if (isPlaying) "재생 중" else "일시정지")
           .setOngoing(true)
          .setVisibility(Notification.VISIBILITY_PUBLIC)
          .setOnlyAlertOnce(true)
          .setAutoCancel(false)
          .setCategory(Notification.CATEGORY_TRANSPORT)
          .setContentIntent(pendingContentIntent)
          .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Prev", prevPI).build())
          .addAction(Notification.Action.Builder(playPauseIcon, playPauseTitle, playPausePI).build())
          .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", nextPI).build())

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          builder.style = Notification.MediaStyle()
              .setShowActionsInCompactView(0, 1, 2)
              .setMediaSession(mediaSession?.sessionToken)
      }

      val notification = builder.build()
      
      TtsService.currentNotification = notification
      if (!TtsService.isRunning) {
          val intent = Intent(this@MainActivity, TtsService::class.java)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              startForegroundService(intent)
          } else {
              startService(intent)
          }
      } else {
          manager.notify(1001, notification)
      }
    }

    private fun cancelNotification() {
      val intent = Intent(this@MainActivity, TtsService::class.java).apply { action = "STOP" }
      startService(intent)
      val manager = getSystemService(NotificationManager::class.java)
      manager.cancel(1001)
    }
    
    fun close() {
      stop()
      showOverlay(false)
      cancelNotification()
    }

    fun stop() {
      waitingForNextChapter = false
      clearPreloadState()
      active = false
      paused = false
      syncPlaybackWakeLock()
      nextChapterNavigationStarted = false
      pendingChapterFromUrl = null
      startPending = false
      generation++
      textToSpeech?.stop()
      webView.evaluateJavascript("window.__npTts&&window.__npTts.clearHighlight()", null)
      updatePlayButton()
      progressView?.progress = 0f
    }

    fun destroy() {
      mainHandler.removeCallbacksAndMessages(null)
      textToSpeech?.stop()
      if (playbackWakeLock.isHeld) playbackWakeLock.release()
      try { if (wifiLock.isHeld) wifiLock.release() } catch (_: Throwable) {}
      clearPreloadState()
      try { preloadWebView.stopLoading(); preloadWebView.destroy() } catch (_: Throwable) {}
      textToSpeech?.shutdown()
      textToSpeech = null
      mediaSession?.release()
      cancelNotification()
    }
  }

  private class TtsProgressView(context: android.content.Context) : View(context) {
    var progress: Float = 0f
      set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private var listener: ((Float) -> Unit)? = null
    fun setOnSeekListener(listener: (Float) -> Unit) { this.listener = listener }

    override fun onDraw(canvas: android.graphics.Canvas) {
      super.onDraw(canvas)
      val centerY = height / 2f
      val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
      paint.color = Color.WHITE
      paint.strokeWidth = 4f
      paint.strokeCap = android.graphics.Paint.Cap.ROUND
      canvas.drawLine(0f, centerY, width.toFloat(), centerY, paint)
      paint.color = Color.rgb(90, 40, 255) 
      canvas.drawLine(0f, centerY, width * progress, centerY, paint)
      paint.style = android.graphics.Paint.Style.FILL
      paint.color = Color.WHITE
      canvas.drawCircle(width * progress, centerY, 12f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
          val newProgress = if (width > 0) (event.x / width.toFloat()).coerceIn(0f, 1f) else 0f
          progress = newProgress
          if (event.actionMasked == MotionEvent.ACTION_UP) listener?.invoke(newProgress)
          return true
        }
      }
      return true
    }
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

// [핵심] 백그라운드 인터넷 끊김(정주행 먹통) 방지 및 미디어 알림을 고정하는 서비스
class TtsService : Service() {
  companion object {
    var currentNotification: Notification? = null
    var isRunning = false
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "STOP") {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
          stopForeground(STOP_FOREGROUND_REMOVE)
      } else {
          @Suppress("DEPRECATION")
          stopForeground(true)
      }
      stopSelf()
      isRunning = false
      return START_NOT_STICKY
    }

    currentNotification?.let {
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(1001, it, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
          startForeground(1001, it)
        }
        isRunning = true
      } catch (e: Exception) {
        e.printStackTrace()
      }
    }
    return START_STICKY
  }
}
