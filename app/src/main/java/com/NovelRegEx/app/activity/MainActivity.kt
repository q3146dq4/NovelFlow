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
import com.NovelRegEx.app.tts.TtsPreferences
import com.NovelRegEx.app.tts.TtsRegexEngine
import com.NovelRegEx.app.tts.TtsRegexStore
import com.NovelRegEx.app.update.UpdateChecker
import com.NovelRegEx.app.update.UpdateNotifier
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
    loadAssetText("novelregex-tts.js")
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
        TtsRegexSettingsActivity.ACTION_REGEX_PREVIEW_PLAY -> {
          val text = intent.getStringExtra(TtsRegexSettingsActivity.EXTRA_REGEX_PREVIEW_TEXT).orEmpty()
          ttsController.playRegexPreview(text)
        }
        TtsRegexSettingsActivity.ACTION_REGEX_PREVIEW_STOP -> ttsController.stopRegexPreview()
      }
    }
  }

  companion object {
    private const val DEFAULT_START_PAGE_URL = "https://novelpia.com/mybook"
    private const val MAX_SCROLL_CACHE = 10
    private const val START_PAGE_KEY = "start_page"
    private val TRUSTED_DOCUMENT_ORIGINS =
      setOf(
        "https://novelpia.com",
        "https://*.novelpia.com",
      )
  }

  private fun isNovelpiaHost(host: String?): Boolean {
    val normalized = host?.trimEnd('.')?.lowercase(Locale.US) ?: return false
    return normalized == "novelpia.com" || normalized.endsWith(".novelpia.com")
  }

  private fun isNovelpiaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    return runCatching {
      val uri = android.net.Uri.parse(url)
      uri.scheme.equals("https", ignoreCase = true) && isNovelpiaHost(uri.host)
    }.getOrDefault(false)
  }

  private fun isViewerUrl(url: String?): Boolean {
    if (!isNovelpiaUrl(url)) return false
    return runCatching {
      val path = android.net.Uri.parse(url).path.orEmpty()
      path == "/viewer" || path.startsWith("/viewer/")
    }.getOrDefault(false)
  }

  private data class TtsSentence(val line: Int, val text: String)

  private data class TtsStartAnchor(
    val text: String,
    val scrollFraction: Float,
  )

  private data class TtsChunkPart(
    val sentenceIndex: Int,
    val start: Int,
    val endExclusive: Int,
  )

  private data class TtsSpeechChunk(
    val text: String,
    val startSentenceIndex: Int,
    val endSentenceIndexExclusive: Int,
    val parts: List<TtsChunkPart>,
    val commaPartIndex: Int? = null,
  )

  private data class QueuedTtsRequest(
    val chunkIndex: Int,
    val chunk: TtsSpeechChunk,
    val resumeAfterChunkIndex: Int = chunkIndex,
    val rolling: Boolean = true,
  )

  inner class ScrollRestoreInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getScrollY(url: String): Int {
      if (!isNovelpiaUrl(url) || !restoringFromViewer) return 0
      restoringFromViewer = false
      return scrollPositions[url] ?: 0
    }
  }

  inner class FilterCssInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun getCosmetic(url: String): String {
      if (!isNovelpiaUrl(url)) return "{\"css\":\"\",\"selectors\":[]}"
      val cosmetic = filterRuntime.getCosmeticForUrl(url)
      return org.json.JSONObject()
        .put("css", cosmetic.css)
        .put("selectors", org.json.JSONArray(cosmetic.selectors))
        .toString()
    }
  }

  inner class TtsJavascriptInterface {
    @Suppress("unused")
    @JavascriptInterface
    fun open() {
      runOnUiThread {
        if (isViewerUrl(webView.url)) ttsController.openAndStart()
      }
    }
    @Suppress("unused")
    @JavascriptInterface
    fun stop() {
      runOnUiThread {
        if (isNovelpiaUrl(webView.url)) ttsController.stop()
      }
    }
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
      addAction(TtsRegexSettingsActivity.ACTION_REGEX_PREVIEW_PLAY)
      addAction(TtsRegexSettingsActivity.ACTION_REGEX_PREVIEW_STOP)
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
      val requestedUrl = intent?.data?.toString()
      webView.loadUrl(requestedUrl?.takeIf(::isNovelpiaUrl) ?: getStartPageUrl())
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

    if (supportsDocumentStartScript) {
      documentStartScripts.forEach { script ->
        WebViewCompat.addDocumentStartJavaScript(webView, script, TRUSTED_DOCUMENT_ORIGINS)
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
        currentPageIsViewer = isViewerUrl(url)
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
        currentPageIsViewer = isViewerUrl(url)
        if (!currentPageIsViewer) ttsController.close()
      }

      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (currentPageIsViewer) return null
        if (request.isForMainFrame) { filterRuntime.preparePage(request.url.toString()) }
        return filterRuntime.maybeBlock(request)
      }

      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url
        if (uri.scheme.equals("https", ignoreCase = true) && isNovelpiaHost(uri.host)) {
          saveScrollPosition()
          return false
        }
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        return true
      }

      override fun onPageFinished(view: WebView, url: String?) {
        swipeRefresh.isRefreshing = false
        if (url == null) return

        lifecycleScope.launch {
          withContext(Dispatchers.IO) { filterRuntime.preparePage(url) }
          if (view.url != url) return@launch

          if (isViewerUrl(url)) {
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
        if (!isViewerUrl(url)) {
          ttsController.clearPreloadedChapter()
        }
      }

      override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        val pageUrl = url ?: return
        if (!isViewerUrl(pageUrl)) return
        installTtsScript(view)
        ttsController.onPreloadPageFinished(pageUrl)
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
    if (isViewerUrl(webView.url)) restoringFromViewer = true
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
          if (isViewerUrl(webView.url)) restoringFromViewer = true
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
    if (isViewerUrl(url)) {
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
    intent.data
      ?.toString()
      ?.takeIf(::isNovelpiaUrl)
      ?.let { webView.loadUrl(it) }
  }

  override fun onResume() {
    super.onResume()
    keepWebViewTimersRunning()
    if (::preloadWebView.isInitialized) preloadWebView.onResume()
    ttsController.refreshCompiledTtsRules()
    val prefs = PreferenceManager.getDefaultSharedPreferences(this)
    swipeRefresh.triggerFraction = prefs.getString("swipe_fraction", null)?.toFloatOrNull() ?: TopSwipeRefreshLayout.DEFAULT_TRIGGER_FRACTION
  }

  override fun onPause() {
    // Keep WebView JavaScript alive only while TTS/binge/preload actually needs it.
    if (!ttsController.needsBackgroundWebView()) {
      webView.onPause()
      if (::preloadWebView.isInitialized) preloadWebView.onPause()
    }
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
    if (isViewerUrl(url)) return
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
    private val playbackChunks = mutableListOf<TtsSpeechChunk>()
    private var currentSentenceIndex = -1
    private var currentChunkIndex = -1
    private var active = false
    private var paused = false
    private var speed = 1.0f
    private var bingeMode = true
    private var waitingForNextChapter = false
    private var generation = 0
    private var retryCount = 0
    private var activeUtteranceId: String? = null
    private var activeChunk: TtsSpeechChunk? = null
    private val queuedTtsRequests = mutableMapOf<String, QueuedTtsRequest>()
    private var queuedThroughChunkIndex = -1
    private var activeRollingPreQueueDepth = 0
    private var currentSentencePartIndex = 0
    private var failedChunkKey: String? = null
    private var failedChunkRetryCount = 0

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

    private var compiledTtsRules: List<TtsRegexEngine.CompiledRule> = emptyList()
    private var regexPreviewGeneration = 0
    private var regexPreviewChunks: List<String> = emptyList()
    private var regexPreviewQueuedThrough = -1
    private var koreanNumberNormalizationEnabled = true
    private val speakableContentRegex = Regex("[가-힣a-zA-Z0-9]")

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

    private fun syncPlaybackLocks() {
      val shouldHoldCpu = active || waitingForNextChapter
      val shouldHoldWifi = preloadInFlight || waitingForNextChapter

      if (shouldHoldCpu) {
        if (!playbackWakeLock.isHeld) playbackWakeLock.acquire()
      } else if (playbackWakeLock.isHeld) {
        playbackWakeLock.release()
      }

      try {
        if (shouldHoldWifi) {
          if (!wifiLock.isHeld) wifiLock.acquire()
        } else if (wifiLock.isHeld) {
          wifiLock.release()
        }
      } catch (_: Throwable) {}
    }

    init { 
      refreshCompiledTtsRules()
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

    fun clearPreloadedChapter() {
      clearPreloadState()
      syncPlaybackLocks()
    }

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
          if (url.isBlank() || !isViewerUrl(url)) return@post
          if (url == webView.url) return@post
          preloadInFlight = true
          syncPlaybackLocks()
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
              syncPlaybackLocks()
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
            syncPlaybackLocks()
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
      syncPlaybackLocks()
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
        sentences.clear()
        val list = root.optJSONArray("sentences") ?: JSONArray()
        for (index in 0 until list.length()) {
          val item = list.optJSONObject(index) ?: continue
          val text = item.optString("text").trim()
          if (text.isNotEmpty()) sentences.add(TtsSentence(line = item.optInt("line", 0), text = text))
        }
        if (sentences.isEmpty()) return
        rebuildPlaybackChunks()
        if (playbackChunks.isEmpty()) return
        currentSentenceIndex = -1
        currentChunkIndex = -1
        currentSentencePartIndex = 0
        active = true
        paused = false
        waitingForNextChapter = false
        advanceGeneration()
        updatePlayButton()
        speakNext()
      } catch (_: Throwable) {}
    }

    fun refreshCompiledTtsRules() {
      koreanNumberNormalizationEnabled = TtsRegexStore.isKoreanNumberEnabled(this@MainActivity)
      compiledTtsRules =
        TtsRegexEngine.compile(
          TtsRegexStore.load(this@MainActivity),
        )
    }

    fun needsBackgroundWebView(): Boolean = active || waitingForNextChapter || preloadInFlight

    fun openAndStart() {
      if (!currentPageIsViewer) return
      refreshCompiledTtsRules()
      ensureOverlay()
      showOverlay(true)
      if (!ttsReady) {
        startPending = true
        Toast.makeText(this@MainActivity, "TTS 엔진을 준비하는 중입니다.", Toast.LENGTH_SHORT).show()
        return
      }
      startPending = false
      retryCount = 0
      captureVisibleStartAnchor { anchor ->
        if (currentPageIsViewer) {
          collectAndStart(0, forNextChapter = false, startAnchor = anchor)
        }
      }
    }

    private fun captureVisibleStartAnchor(onResult: (TtsStartAnchor) -> Unit) {
      val js =
        """
        (function() {
          try {
            var scroller = document.scrollingElement || document.documentElement || document.body;
            var maxScroll = Math.max(1, (scroller ? scroller.scrollHeight : 1) - window.innerHeight);
            var scrollTop = scroller ? scroller.scrollTop : window.scrollY;
            var fraction = Math.max(0, Math.min(1, scrollTop / maxScroll));
            var ys = [0.45, 0.60, 0.30];
            var text = '';

            for (var i = 0; i < ys.length && !text; i++) {
              var x = Math.max(1, window.innerWidth * 0.5);
              var y = Math.max(1, window.innerHeight * ys[i]);
              var range = document.caretRangeFromPoint ? document.caretRangeFromPoint(x, y) : null;
              if (range && range.startContainer) {
                var node = range.startContainer;
                var raw = node.nodeType === 3 ? (node.nodeValue || '') : (node.textContent || '');
                var offset = Math.max(0, Math.min(raw.length, range.startOffset || 0));
                var from = Math.max(0, offset - 70);
                var to = Math.min(raw.length, offset + 110);
                text = raw.slice(from, to).replace(/\s+/g, ' ').trim();
              }

              if (!text) {
                var element = document.elementFromPoint(x, y);
                while (element && element !== document.body) {
                  var candidate = (element.innerText || element.textContent || '').replace(/\s+/g, ' ').trim();
                  if (candidate.length >= 2 && candidate.length <= 240) {
                    text = candidate;
                    break;
                  }
                  element = element.parentElement;
                }
              }
            }

            return JSON.stringify({ text: text, fraction: fraction });
          } catch (e) {
            return JSON.stringify({ text: '', fraction: 0 });
          }
        })();
        """.trimIndent()

      webView.evaluateJavascript(js) { rawResult ->
        mainHandler.post {
          val anchor =
            runCatching {
              val decoded = JSONTokener(rawResult ?: "\"\"").nextValue() as? String
              val root = org.json.JSONObject(decoded.orEmpty())
              TtsStartAnchor(
                text = root.optString("text").trim(),
                scrollFraction = root.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f),
              )
            }.getOrElse { TtsStartAnchor("", 0f) }
          onResult(anchor)
        }
      }
    }

    private fun collectAndStart(
      retry: Int,
      forNextChapter: Boolean,
      startAnchor: TtsStartAnchor? = null,
    ) {
      if (!currentPageIsViewer) return
      webView.evaluateJavascript("window.__npTts&&window.__npTts.collect()") { result ->
        mainHandler.post {
          if (result.isNullOrBlank() || result == "null" || result == "\"\"") {
            if (retry < 60) {
              mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter, startAnchor) }, 250L)
            } else if (forNextChapter && waitingForNextChapter) {
              webView.reload()
              mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true, startAnchor = null) }, 800L)
            } else {
              Toast.makeText(this@MainActivity, "회차 본문을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
            }
            return@post
          }
          try {
            val decoded = JSONTokener(result).nextValue() as? String
            if (decoded.isNullOrBlank()) {
              if (retry < 60) {
                mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter, startAnchor) }, 250L)
              } else if (forNextChapter && waitingForNextChapter) {
                webView.reload()
                mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true, startAnchor = null) }, 800L)
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
                mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter, startAnchor) }, 250L)
              } else if (forNextChapter && waitingForNextChapter) {
                webView.reload()
                mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true, startAnchor = null) }, 800L)
              } else {
                Toast.makeText(this@MainActivity, "읽을 수 있는 본문을 찾지 못했습니다.", Toast.LENGTH_LONG).show()
              }
              return@post
            }

            rebuildPlaybackChunks()
            if (playbackChunks.isEmpty()) {
              Toast.makeText(this@MainActivity, "읽을 수 있는 TTS 청크를 만들지 못했습니다.", Toast.LENGTH_LONG).show()
              return@post
            }

            val initialChunkIndex =
              if (forNextChapter) 0 else resolveInitialChunkIndex(startAnchor)
            currentSentenceIndex = -1
            currentChunkIndex = initialChunkIndex - 1
            currentSentencePartIndex = 0
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
            syncPlaybackLocks()
            advanceGeneration()
            updatePlayButton()
            speakNext()
            mainHandler.postDelayed({ prepareNextChapterPreload() }, 1200L)
          } catch (error: Exception) {
            if (retry < 60) {
              mainHandler.postDelayed({ collectAndStart(retry + 1, forNextChapter, startAnchor) }, 250L)
            } else if (forNextChapter && waitingForNextChapter) {
              webView.reload()
              mainHandler.postDelayed({ collectAndStart(0, forNextChapter = true, startAnchor = null) }, 800L)
            } else {
              Toast.makeText(this@MainActivity, "TTS 본문 분석에 실패했습니다: ${error.message.orEmpty()}", Toast.LENGTH_LONG).show()
            }
          }
        }
      }
    }

    private fun resolveInitialChunkIndex(anchor: TtsStartAnchor?): Int {
      if (playbackChunks.isEmpty()) return 0
      if (anchor == null) return 0

      val sentenceIndex = findSentenceIndexNearAnchor(anchor.text)
      if (sentenceIndex >= 0) {
        val chunkIndex =
          playbackChunks.indexOfFirst { chunk ->
            sentenceIndex >= chunk.startSentenceIndex &&
              sentenceIndex < chunk.endSentenceIndexExclusive
          }
        if (chunkIndex >= 0) return chunkIndex
      }

      return (
        anchor.scrollFraction.coerceIn(0f, 1f) * playbackChunks.lastIndex
      ).roundToInt().coerceIn(0, playbackChunks.lastIndex)
    }

    private fun findSentenceIndexNearAnchor(rawAnchor: String): Int {
      val anchor = normalizeAnchorText(rawAnchor)
      if (anchor.length < 2) return -1

      sentences.forEachIndexed { index, sentence ->
        val candidate = normalizeAnchorText(sentence.text)
        if (candidate.length >= 2 && (candidate.contains(anchor) || anchor.contains(candidate))) {
          return index
        }
      }

      val compactAnchor = anchor.replace(" ", "")
      if (compactAnchor.length < 8) return -1
      val windowLength = minOf(32, compactAnchor.length)
      val starts =
        listOf(
          0,
          ((compactAnchor.length - windowLength) / 2).coerceAtLeast(0),
          (compactAnchor.length - windowLength).coerceAtLeast(0),
        ).distinct()

      for (start in starts) {
        val needle = compactAnchor.substring(start, start + windowLength)
        val index =
          sentences.indexOfFirst { sentence ->
            normalizeAnchorText(sentence.text).replace(" ", "").contains(needle)
          }
        if (index >= 0) return index
      }

      return -1
    }

    private fun normalizeAnchorText(value: String): String =
      value.replace(Regex("\\s+"), " ").trim()

    private fun clearQueuedSpeechState() {
      queuedTtsRequests.clear()
      queuedThroughChunkIndex = -1
      activeRollingPreQueueDepth = 0
      activeUtteranceId = null
      activeChunk = null
    }

    private fun advanceGeneration() {
      generation += 1
      clearQueuedSpeechState()
    }

    private fun splitRegexPreviewText(text: String): List<String> {
      val trimmed = text.trim()
      if (trimmed.isEmpty()) return emptyList()

      // Preview is deliberately a single utterance. Tiny QUEUE_ADD fragments can
      // trigger multilingual auto-detection quirks in some custom Android TTS
      // engines even when ordinary novel playback sounds correct.
      return listOf(trimmed.take(TextToSpeech.getMaxSpeechInputLength()))
    }

    private fun clearRegexPreviewState() {
      regexPreviewGeneration += 1
      regexPreviewChunks = emptyList()
      regexPreviewQueuedThrough = -1
    }

    fun stopRegexPreview() {
      clearRegexPreviewState()
      textToSpeech?.stop()
    }

    fun playRegexPreview(text: String) {
      val chunks = splitRegexPreviewText(text)
      if (chunks.isEmpty()) return

      // 설정 화면에서도 본문과 같은 TextToSpeech 인스턴스를 쓴다.
      // 별도 TextToSpeech 클라이언트를 만들면 일부 커스텀 엔진에서 재생이 깨질 수 있다.
      stop()
      clearRegexPreviewState()
      if (!ttsReady || textToSpeech == null) {
        Toast.makeText(this@MainActivity, "TTS 엔진이 아직 준비되지 않았습니다.", Toast.LENGTH_SHORT).show()
        return
      }

      regexPreviewChunks = chunks
      textToSpeech?.setLanguage(Locale.KOREAN)
      textToSpeech?.setSpeechRate(speed)

      val first = enqueueRegexPreviewChunk(0, TextToSpeech.QUEUE_FLUSH)
      if (!first) {
        clearRegexPreviewState()
        Toast.makeText(this@MainActivity, "TTS 테스트 재생에 실패했습니다.", Toast.LENGTH_SHORT).show()
        return
      }
      fillRegexPreviewQueue(anchorIndex = 0)
    }

    private fun regexPreviewUtteranceId(index: Int): String =
      "np_regex_preview_${regexPreviewGeneration}_${index}"

    private fun parseRegexPreviewUtteranceId(utteranceId: String): Pair<Int, Int>? {
      val prefix = "np_regex_preview_"
      if (!utteranceId.startsWith(prefix)) return null
      val parts = utteranceId.removePrefix(prefix).split('_')
      if (parts.size != 2) return null
      val previewGeneration = parts[0].toIntOrNull() ?: return null
      val index = parts[1].toIntOrNull() ?: return null
      return previewGeneration to index
    }

    private fun enqueueRegexPreviewChunk(
      index: Int,
      queueMode: Int,
    ): Boolean {
      if (index !in regexPreviewChunks.indices) return false
      val text = regexPreviewChunks[index]
      if (text.isBlank()) return false

      val utteranceId = regexPreviewUtteranceId(index)
      val params =
        Bundle().apply {
          putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }
      val result =
        textToSpeech?.speak(
          text,
          queueMode,
          params,
          utteranceId,
        ) ?: TextToSpeech.ERROR
      if (result == TextToSpeech.ERROR) return false

      regexPreviewQueuedThrough = maxOf(regexPreviewQueuedThrough, index)
      return true
    }

    private fun fillRegexPreviewQueue(anchorIndex: Int) {
      if (regexPreviewChunks.isEmpty()) return

      val depth = configuredRollingPreQueueDepth().coerceAtLeast(0)
      val targetTail =
        (anchorIndex + depth)
          .coerceAtMost(regexPreviewChunks.lastIndex)
      var nextIndex = maxOf(regexPreviewQueuedThrough + 1, anchorIndex + 1)

      while (nextIndex <= targetTail) {
        if (!enqueueRegexPreviewChunk(nextIndex, TextToSpeech.QUEUE_ADD)) return
        nextIndex += 1
      }
    }

    private fun handleRegexPreviewDone(
      previewGeneration: Int,
      index: Int,
    ) {
      mainHandler.post {
        if (previewGeneration != regexPreviewGeneration) return@post
        if (index !in regexPreviewChunks.indices) return@post

        if (index >= regexPreviewChunks.lastIndex) {
          clearRegexPreviewState()
          return@post
        }

        val nextIndex = index + 1
        if (regexPreviewQueuedThrough < nextIndex) {
          if (!enqueueRegexPreviewChunk(nextIndex, TextToSpeech.QUEUE_ADD)) {
            clearRegexPreviewState()
            Toast.makeText(this@MainActivity, "TTS 테스트 재생 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
            return@post
          }
        }
        fillRegexPreviewQueue(anchorIndex = nextIndex)
      }
    }

    private fun handleRegexPreviewError(
      previewGeneration: Int,
      index: Int,
    ) {
      mainHandler.post {
        if (previewGeneration != regexPreviewGeneration) return@post
        clearRegexPreviewState()
        textToSpeech?.stop()
        Toast.makeText(
          this@MainActivity,
          "TTS 테스트 ${index + 1}번째 조각 재생에 실패했습니다.",
          Toast.LENGTH_SHORT,
        ).show()
      }
    }

    private fun configuredRollingPreQueueDepth(): Int =
      TtsPreferences.getRollingPrequeueDepth(this@MainActivity)

    fun speakNext(queueMode: Int = TextToSpeech.QUEUE_FLUSH) {
      if (!active || paused) return
      currentChunkIndex++
      if (currentChunkIndex >= playbackChunks.size) {
        finishEpisode()
        return
      }
      val chunk = playbackChunks[currentChunkIndex]
      currentSentenceIndex = chunk.startSentenceIndex
      currentSentencePartIndex = chunk.commaPartIndex ?: 0
      speakCurrent(queueMode = queueMode)
    }

    /**
     * Apply the enabled TTS-regex list from top to bottom.
     * Pronunciation, units, fractions, decimals and whitespace normalization are
     * all editable default user rules in TtsRegexStore; there is no hidden second pass.
     */
    private fun prepareTtsText(original: String): String =
      TtsRegexEngine.applyCompiled(
        original = original,
        rules = compiledTtsRules,
        koreanNumberEnabled = koreanNumberNormalizationEnabled,
      )

    private fun hasSpeakableContent(text: String): Boolean =
      speakableContentRegex.containsMatchIn(text)

    private fun splitAtCommas(text: String): List<String> {
      val result = mutableListOf<String>()
      var start = 0

      text.forEachIndexed { index, character ->
        val isComma = character == ',' || character == '，' || character == '、'
        val isNumberSeparator =
          character == ',' &&
            index > 0 &&
            index + 1 < text.length &&
            text[index - 1].isDigit() &&
            text[index + 1].isDigit()

        if (isComma && !isNumberSeparator) {
          text.substring(start, index + 1).trim().takeIf { it.isNotEmpty() }?.let(result::add)
          start = index + 1
        }
      }

      text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
      return result.ifEmpty { listOf(text) }
    }

    private fun buildCommaChunk(startSentenceIndex: Int, requestedPartIndex: Int): TtsSpeechChunk {
      val commaParts = splitAtCommas(sentences[startSentenceIndex].text)
      val partIndex = requestedPartIndex.coerceIn(0, commaParts.lastIndex)
      val text = prepareTtsText(commaParts[partIndex])

      return TtsSpeechChunk(
        text = text,
        startSentenceIndex = startSentenceIndex,
        endSentenceIndexExclusive = startSentenceIndex + 1,
        parts =
          if (hasSpeakableContent(text)) {
            listOf(TtsChunkPart(sentenceIndex = startSentenceIndex, start = 0, endExclusive = text.length))
          } else {
            emptyList()
          },
        commaPartIndex = partIndex,
      )
    }

    private fun buildCombinedChunk(
      startSentenceIndex: Int,
      requestedEndSentenceIndexExclusive: Int,
    ): TtsSpeechChunk {
      val text = StringBuilder()
      val parts = mutableListOf<TtsChunkPart>()
      val maxInputLength = TextToSpeech.getMaxSpeechInputLength()
      var endSentenceIndexExclusive = startSentenceIndex

      for (sentenceIndex in startSentenceIndex until requestedEndSentenceIndexExclusive.coerceAtMost(sentences.size)) {
        val prepared = prepareTtsText(sentences[sentenceIndex].text)

        endSentenceIndexExclusive = sentenceIndex + 1
        if (prepared.isBlank() || !hasSpeakableContent(prepared)) continue

        val separatorLength = if (text.isEmpty()) 0 else 1
        val candidateLength = text.length + separatorLength + prepared.length

        // Android TTS 입력 한계를 넘지 않도록 설정한 문장 수보다 먼저 청크를 끝낸다.
        // 단, 문장 하나 자체가 한계보다 긴 기존 콘텐츠는 임의로 잘라 읽지 않는다.
        if (text.isNotEmpty() && candidateLength > maxInputLength) {
          endSentenceIndexExclusive = sentenceIndex
          break
        }

        if (separatorLength > 0) text.append(' ')
        val partStart = text.length
        text.append(prepared)
        parts +=
          TtsChunkPart(
            sentenceIndex = sentenceIndex,
            start = partStart,
            endExclusive = text.length,
          )
      }

      return TtsSpeechChunk(
        text = text.toString(),
        startSentenceIndex = parts.firstOrNull()?.sentenceIndex ?: startSentenceIndex,
        endSentenceIndexExclusive = endSentenceIndexExclusive.coerceAtLeast(startSentenceIndex + 1),
        parts = parts,
      )
    }

    private fun buildSpeechChunk(
      startSentenceIndex: Int,
      mode: String = TtsPreferences.getChunkMode(this@MainActivity),
    ): TtsSpeechChunk {
      if (mode == TtsPreferences.CHUNK_MODE_COMMA) {
        return buildCommaChunk(startSentenceIndex, currentSentencePartIndex)
      }

      val requestedEnd =
        when (mode) {
          TtsPreferences.CHUNK_MODE_SENTENCE -> startSentenceIndex + 1
          TtsPreferences.CHUNK_MODE_PARAGRAPH -> {
            val line = sentences[startSentenceIndex].line
            var end = startSentenceIndex + 1
            while (end < sentences.size && sentences[end].line == line) end++
            end
          }
          else -> startSentenceIndex + 1
        }

      return buildCombinedChunk(startSentenceIndex, requestedEnd)
    }

    private fun rebuildPlaybackChunks() {
      playbackChunks.clear()

      when (TtsPreferences.getChunkMode(this@MainActivity)) {
        TtsPreferences.CHUNK_MODE_COMMA -> {
          sentences.indices.forEach { sentenceIndex ->
            val commaParts = splitAtCommas(sentences[sentenceIndex].text)
            commaParts.indices.forEach { partIndex ->
              buildCommaChunk(sentenceIndex, partIndex)
                .takeIf { it.text.isNotBlank() && it.parts.isNotEmpty() }
                ?.let(playbackChunks::add)
            }
          }
        }

        TtsPreferences.CHUNK_MODE_PARAGRAPH -> {
          var start = 0
          while (start < sentences.size) {
            val line = sentences[start].line
            var lineEnd = start + 1
            while (lineEnd < sentences.size && sentences[lineEnd].line == line) lineEnd++

            var chunkStart = start
            while (chunkStart < lineEnd) {
              val chunk = buildCombinedChunk(chunkStart, lineEnd)
              if (chunk.text.isNotBlank() && chunk.parts.isNotEmpty()) playbackChunks += chunk
              val nextStart = chunk.endSentenceIndexExclusive.coerceAtLeast(chunkStart + 1)
              chunkStart = nextStart
            }
            start = lineEnd
          }
        }

        else -> {
          sentences.indices.forEach { sentenceIndex ->
            buildCombinedChunk(sentenceIndex, sentenceIndex + 1)
              .takeIf { it.text.isNotBlank() && it.parts.isNotEmpty() }
              ?.let(playbackChunks::add)
          }
        }
      }
    }

    private fun speakCurrent(
      modeOverride: String? = null,
      queueMode: Int = TextToSpeech.QUEUE_FLUSH,
    ) {
      if (!active || paused || currentChunkIndex !in playbackChunks.indices) return
      syncPlaybackLocks()

      val chunkIndex = currentChunkIndex
      val chunk =
        if (modeOverride == null) {
          playbackChunks[chunkIndex]
        } else {
          buildSpeechChunk(currentSentenceIndex, modeOverride)
        }

      if (chunk.text.isBlank() || chunk.parts.isEmpty()) {
        mainHandler.post { advanceAfterChunk(chunk) }
        return
      }

      currentSentenceIndex = chunk.startSentenceIndex
      currentSentencePartIndex = chunk.commaPartIndex ?: 0

      if (queueMode == TextToSpeech.QUEUE_FLUSH) {
        clearQueuedSpeechState()
        queuedThroughChunkIndex = chunkIndex - 1
        activeRollingPreQueueDepth =
          if (modeOverride == null) configuredRollingPreQueueDepth() else 0
      }

      textToSpeech?.setSpeechRate(speed)

      val utteranceId =
        enqueueTtsRequest(
          chunkIndex = chunkIndex,
          chunk = chunk,
          queueMode = queueMode,
          resumeAfterChunkIndex = chunkIndex,
          rolling = modeOverride == null,
        ) ?: run {
          mainHandler.post { handleChunkFailure(chunk) }
          return
        }

      // 기존 UI 반응성을 유지한다. 실제 다음 청크로 넘어갈 때는 onStart에서 다시 갱신된다.
      activeUtteranceId = utteranceId
      activeChunk = chunk
      mainHandler.post {
        updateProgress(chunkIndex)
        highlightCurrentChunk()
      }

      if (modeOverride == null && activeRollingPreQueueDepth > 0) {
        fillRollingPreQueue(chunkIndex)
      }
    }

    private fun enqueueTtsRequest(
      chunkIndex: Int,
      chunk: TtsSpeechChunk,
      queueMode: Int,
      resumeAfterChunkIndex: Int = chunkIndex,
      rolling: Boolean = true,
    ): String? {
      if (chunkIndex !in playbackChunks.indices || chunk.text.isBlank() || chunk.parts.isEmpty()) return null

      val utteranceId = "np_tts_${generation}_${chunkIndex}"
      if (queuedTtsRequests.containsKey(utteranceId)) return utteranceId

      val request =
        QueuedTtsRequest(
          chunkIndex = chunkIndex,
          chunk = chunk,
          resumeAfterChunkIndex = resumeAfterChunkIndex,
          rolling = rolling,
        )
      queuedTtsRequests[utteranceId] = request

      val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId) }
      val result = textToSpeech?.speak(chunk.text, queueMode, params, utteranceId) ?: TextToSpeech.ERROR
      if (result == TextToSpeech.ERROR) {
        queuedTtsRequests.remove(utteranceId)
        return null
      }

      if (rolling) queuedThroughChunkIndex = maxOf(queuedThroughChunkIndex, chunkIndex)
      return utteranceId
    }

    /**
     * 현재 재생 청크 뒤로 설정된 개수만큼 QUEUE_ADD 요청을 항상 유지한다.
     * 예: depth=3이면 [현재][+1][+2][+3], 현재가 끝나면 맨 뒤에 +4를 보충한다.
     */
    private fun fillRollingPreQueue(anchorChunkIndex: Int) {
      if (!active || paused || activeRollingPreQueueDepth <= 0 || playbackChunks.isEmpty()) return

      val targetTail =
        (anchorChunkIndex + activeRollingPreQueueDepth)
          .coerceAtMost(playbackChunks.lastIndex)
      var nextIndex = maxOf(queuedThroughChunkIndex + 1, anchorChunkIndex + 1)

      while (nextIndex <= targetTail) {
        val chunk = playbackChunks[nextIndex]
        val enqueued =
          enqueueTtsRequest(
            chunkIndex = nextIndex,
            chunk = chunk,
            queueMode = TextToSpeech.QUEUE_ADD,
            rolling = true,
          )
        if (enqueued == null) {
          // Tail pre-queue is speculative: do not interrupt audio that is already playing.
          // A missing immediate next chunk is retried from onDone().
          return
        }
        nextIndex++
      }
    }

    private fun chunkFailureKey(chunk: TtsSpeechChunk): String =
      "${chunk.startSentenceIndex}:${chunk.endSentenceIndexExclusive}:${chunk.commaPartIndex ?: -1}"

    /**
     * 짧은 쉼표 조각을 간헐적으로 거부하는 TTS 엔진이 있다. 실패한 조각을 그대로
     * 건너뛰지 않고 두 번 재시도하며, 그래도 실패하면 해당 문장 전체로 재시도한다.
     */
    private fun handleChunkFailure(chunk: TtsSpeechChunk) {
      if (!active || paused) return

      textToSpeech?.stop()
      clearQueuedSpeechState()
      val key = chunkFailureKey(chunk)
      if (failedChunkKey != key) {
        failedChunkKey = key
        failedChunkRetryCount = 0
      }

      if (failedChunkRetryCount < 2) {
        failedChunkRetryCount++
        advanceGeneration()
        val retryGeneration = generation
        currentSentenceIndex = chunk.startSentenceIndex
        currentSentencePartIndex = chunk.commaPartIndex ?: 0
        mainHandler.postDelayed({
          if (active && !paused && generation == retryGeneration &&
            currentSentenceIndex == chunk.startSentenceIndex
          ) {
            speakCurrent()
          }
        }, 120L)
        return
      }

      failedChunkKey = null
      failedChunkRetryCount = 0
      advanceGeneration()
      currentSentenceIndex = chunk.startSentenceIndex
      currentSentencePartIndex = 0

      if (chunk.commaPartIndex == null && chunk.parts.size <= 1) {
        Toast.makeText(this@MainActivity, "TTS 엔진이 이 문장을 재생하지 못했습니다.", Toast.LENGTH_SHORT).show()
        advanceAfterChunk(chunk)
        return
      }

      // 쉼표 조각 또는 큰 문단 청크가 계속 실패해도 원문을 건너뛰지는 않는다.
      // 문장 하나로 낮춰 재생하므로 쉼표 모드에서는 앞부분이 드물게 중복될 수 있다.
      if (chunk.commaPartIndex != null) {
        val lastChunkOfSentence = playbackChunks.indexOfLast {
          it.startSentenceIndex == chunk.startSentenceIndex
        }
        if (lastChunkOfSentence >= currentChunkIndex) currentChunkIndex = lastChunkOfSentence
      }
      speakCurrent(modeOverride = TtsPreferences.CHUNK_MODE_SENTENCE)
    }

    private fun advanceAfterChunk(chunk: TtsSpeechChunk) {
      activeUtteranceId = null
      activeChunk = null
      currentSentencePartIndex = 0

      if (!active || paused) return

      speakNext(queueMode = TextToSpeech.QUEUE_FLUSH)
    }

    override fun onStart(utteranceId: String) {
      if (parseRegexPreviewUtteranceId(utteranceId) != null) return
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      mainHandler.post {
        if (!active || paused || parsed.first != generation) return@post
        val request = queuedTtsRequests[utteranceId] ?: return@post
        if (request.chunkIndex !in playbackChunks.indices) return@post

        activeUtteranceId = utteranceId
        activeChunk = request.chunk
        currentChunkIndex = request.chunkIndex
        currentSentenceIndex = request.chunk.startSentenceIndex
        currentSentencePartIndex = request.chunk.commaPartIndex ?: 0
        updateProgress(currentChunkIndex)
        highlightCurrentChunk()
      }
    }

    override fun onRangeStart(
      utteranceId: String,
      start: Int,
      end: Int,
      frame: Int,
    ) {
      if (parseRegexPreviewUtteranceId(utteranceId) != null) return
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      activeChunk ?: return
      if (utteranceId != activeUtteranceId) return
      // 선택한 청크 안에서 rangeStart가 이동해도 탐색 위치와 강조는 청크 단위로 유지한다.
    }

    override fun onDone(utteranceId: String) {
      parseRegexPreviewUtteranceId(utteranceId)?.let { (previewGeneration, index) ->
        handleRegexPreviewDone(previewGeneration, index)
        return
      }
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      mainHandler.post {
        if (!active || paused || parsed.first != generation) return@post
        val request = queuedTtsRequests.remove(utteranceId) ?: return@post

        if (utteranceId == activeUtteranceId) {
          activeUtteranceId = null
          activeChunk = null
        }
        failedChunkKey = null
        failedChunkRetryCount = 0

        if (!request.rolling || activeRollingPreQueueDepth <= 0) {
          currentChunkIndex = request.resumeAfterChunkIndex
          currentSentencePartIndex = 0
          if (currentChunkIndex >= playbackChunks.lastIndex) {
            finishEpisode()
          } else {
            advanceAfterChunk(request.chunk)
          }
          return@post
        }

        // Android TTS normally starts the already queued next chunk immediately.
        // If speculative pre-queue previously failed before the immediate next chunk,
        // retry that head now instead of skipping it.
        if (request.chunkIndex >= playbackChunks.lastIndex) {
          finishEpisode()
          return@post
        }

        val nextChunkIndex = request.chunkIndex + 1
        val nextAlreadyQueued = queuedTtsRequests.values.any { it.chunkIndex == nextChunkIndex }
        if (!nextAlreadyQueued) {
          val nextChunk = playbackChunks[nextChunkIndex]
          val enqueued =
            enqueueTtsRequest(
              chunkIndex = nextChunkIndex,
              chunk = nextChunk,
              queueMode = TextToSpeech.QUEUE_ADD,
              rolling = true,
            )
          if (enqueued == null) {
            currentChunkIndex = nextChunkIndex
            currentSentenceIndex = nextChunk.startSentenceIndex
            currentSentencePartIndex = nextChunk.commaPartIndex ?: 0
            clearQueuedSpeechState()
            handleChunkFailure(nextChunk)
            return@post
          }
        }
        fillRollingPreQueue(nextChunkIndex)
      }
    }

    @Deprecated("Deprecated by Android API")
    override fun onError(utteranceId: String) { onError(utteranceId, TextToSpeech.ERROR) }

    override fun onError(utteranceId: String, errorCode: Int) {
      parseRegexPreviewUtteranceId(utteranceId)?.let { (previewGeneration, index) ->
        handleRegexPreviewError(previewGeneration, index)
        return
      }
      val parsed = parseUtteranceId(utteranceId) ?: return
      if (parsed.first != generation) return
      mainHandler.post {
        if (!active || paused || parsed.first != generation) return@post
        val request = queuedTtsRequests.remove(utteranceId) ?: return@post

        // 실패 이후에 미리 넣어 둔 요청이 건너뛰어 재생되지 않도록 큐 전체를 재구성한다.
        textToSpeech?.stop()
        currentChunkIndex = request.chunkIndex
        currentSentenceIndex = request.chunk.startSentenceIndex
        currentSentencePartIndex = request.chunk.commaPartIndex ?: 0
        clearQueuedSpeechState()
        handleChunkFailure(request.chunk)
      }
    }

    override fun onStop(utteranceId: String, interrupted: Boolean) {}

    private fun parseUtteranceId(id: String): Pair<Int, Int>? {
      val parts = id.split('_')
      if (parts.size != 4) return null
      return try { parts[2].toInt() to parts[3].toInt() } catch (_: NumberFormatException) { null }
    }

    private fun highlightCurrentChunk() {
      val chunk = activeChunk ?: return
      val commaPartIndex = chunk.commaPartIndex ?: -1
      webView.evaluateJavascript(
        "window.__npTts&&window.__npTts.highlightChunk(${chunk.startSentenceIndex},${chunk.endSentenceIndexExclusive},$commaPartIndex)",
        null,
      )
    }

    private fun updateProgress(chunkIndex: Int) {
      if (chunkIndex !in playbackChunks.indices) {
        counterText?.text = "0 / 0"
        progressView?.progress = 0f
        return
      }

      val chunk = playbackChunks[chunkIndex]
      counterText?.text = "${chunkIndex + 1} / ${playbackChunks.size}"
      progressView?.progress =
        if (playbackChunks.size <= 1) 0f else chunkIndex.toFloat() / playbackChunks.lastIndex
      webView.evaluateJavascript(
        "window.__npTts&&window.__npTts.scrollToLine(${sentences[chunk.startSentenceIndex].line})",
        null,
      )
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
        syncPlaybackLocks()
        updatePlayButton()
        if (tryStartFromPreloadedChapter()) return
        requestNextChapter(0)
      } else {
        active = false
        paused = false
        waitingForNextChapter = false
        syncPlaybackLocks()
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
            if (value != currentUrl && isViewerUrl(value)) {
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
      if (playbackChunks.isEmpty()) return
      currentChunkIndex = (currentChunkIndex - 2).coerceAtLeast(-1)
      currentSentencePartIndex = 0
      advanceGeneration()
      textToSpeech?.stop()
      active = true
      paused = false
      syncPlaybackLocks()
      speakNext()
    }

    fun next() {
      if (playbackChunks.isEmpty()) return
      currentSentencePartIndex = 0
      advanceGeneration()
      textToSpeech?.stop()
      active = true
      paused = false
      syncPlaybackLocks()
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
          syncPlaybackLocks()
          advanceGeneration()
          if (currentChunkIndex in playbackChunks.indices) {
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
        syncPlaybackLocks()
        advanceGeneration()
        if (currentChunkIndex in playbackChunks.indices) {
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
      syncPlaybackLocks()
      advanceGeneration()
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
          val restartIndex = currentChunkIndex
          if (restartIndex in playbackChunks.indices) {
            textToSpeech?.stop()
            advanceGeneration()
            currentChunkIndex = restartIndex
            val chunk = playbackChunks[restartIndex]
            currentSentenceIndex = chunk.startSentenceIndex
            currentSentencePartIndex = chunk.commaPartIndex ?: 0
            speakCurrent()
          }
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
      if (playbackChunks.isEmpty()) return

      val targetIndex = (
        fraction.coerceIn(0f, 1f) * (playbackChunks.size - 1)
      ).roundToInt().coerceIn(0, playbackChunks.lastIndex)

      // 탐색 전 재생 상태를 보존한다.
      val wasPlaying = active && !paused

      advanceGeneration()
      textToSpeech?.stop()

      if (wasPlaying) {
        // 재생 중 탐색: 선택한 위치에서 즉시 계속 재생
        currentChunkIndex = targetIndex
        val targetChunk = playbackChunks[targetIndex]
        currentSentenceIndex = targetChunk.startSentenceIndex
        currentSentencePartIndex = targetChunk.commaPartIndex ?: 0
        active = true
        paused = false
        speakCurrent()
      } else {
        // 정지/일시정지 상태 탐색: 위치만 옮기고 자동 재생하지 않음
        currentChunkIndex = targetIndex
        val targetChunk = playbackChunks[targetIndex]
        currentSentenceIndex = targetChunk.startSentenceIndex
        currentSentencePartIndex = targetChunk.commaPartIndex ?: 0
        active = false
        paused = true

        updateProgress(targetIndex)
        activeChunk = targetChunk
        highlightCurrentChunk()
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
          .setSmallIcon(R.drawable.ic_notification_novelregex)
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
      clearRegexPreviewState()
      waitingForNextChapter = false
      clearPreloadState()
      active = false
      paused = false
      syncPlaybackLocks()
      nextChapterNavigationStarted = false
      pendingChapterFromUrl = null
      startPending = false
      advanceGeneration()
      textToSpeech?.stop()
      activeUtteranceId = null
      activeChunk = null
      currentSentencePartIndex = 0
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
