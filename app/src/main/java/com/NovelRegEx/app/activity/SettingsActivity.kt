package com.NovelRegEx.app.activity

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.NovelRegEx.app.R
import com.NovelRegEx.app.bookmark.BookmarkRepository
import com.NovelRegEx.app.filter.FilterPreferences
import com.NovelRegEx.app.filter.FilterRuntime
import com.NovelRegEx.app.setting.BackupSettings
import com.NovelRegEx.app.setting.SettingBackup
import com.NovelRegEx.app.setting.SettingBackupCodec
import com.NovelRegEx.app.tts.TtsPreferences
import com.NovelRegEx.app.update.UpdateChecker
import com.NovelRegEx.app.update.UpdateInfo
import com.NovelRegEx.app.update.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

class SettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    if (savedInstanceState == null) {
      supportFragmentManager.beginTransaction().replace(R.id.settings_container, SettingsFragment()).commit()
    }
    supportActionBar?.setDisplayHomeAsUpEnabled(true)
    title = getString(R.string.title_activity_settings)
  }

  override fun onSupportNavigateUp(): Boolean {
    finish()
    return true
  }

  class SettingsFragment : PreferenceFragmentCompat() {
    companion object {
      // @RequiresApi(33) 회피를 위해 문자열 상수로 선언
      private const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
      private const val START_PAGE_KEY = "start_page"
      private const val START_PAGE_HOME_URL = "https://novelpia.com"
      private const val START_PAGE_LAST_VIEW_URL = "https://novelpia.com/mybook/last_view"
      private const val START_PAGE_MYBOOK_URL = "https://novelpia.com/mybook"
      private const val TAG = "SettingsBackup"
    }

    private val exportSettingsLauncher =
      registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportSettings(uri)
      }

    private val importSettingsLauncher =
      registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importSettings(uri)
      }

    private val notificationPermissionLauncher =
      registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!isAdded) return@registerForActivityResult
        if (!granted) {
          if (!shouldShowRequestPermissionRationale(POST_NOTIFICATIONS)) {
            showGoToSettingsDialog()
          } else {
            Toast.makeText(requireContext(), R.string.msg_notification_permission_denied, Toast.LENGTH_LONG).show()
          }
        }
        pendingPermissionAction?.invoke()
        pendingPermissionAction = null
      }

    private var pendingUpdateInfo: UpdateInfo? = null
    private var pendingPermissionAction: (() -> Unit)? = null

    override fun onCreatePreferences(
      savedInstanceState: Bundle?,
      rootKey: String?,
    ) {
      setPreferencesFromResource(R.xml.root_preferences, rootKey)
      setupStartPagePref()
      setupLinkSettingsPref()
      setupFilterPrefs()
      setupSettingBackupPrefs()
      setupDataPrefs()
      setupVersionPref()
      setupUpdatePrefs()
    }

    override fun onResume() {
      super.onResume()
      refreshStartPagePref()
      refreshLinkSettingsPref()
      lifecycleScope.launch {
        refreshFilterPrefs()
        refreshStorageSummaries()
        refreshUpdatePrefs()
      }
    }

    // region 시작 페이지

    private fun setupStartPagePref() {
      findPreference<Preference>(START_PAGE_KEY)?.setOnPreferenceClickListener {
        showStartPageDialog()
        true
      }
      refreshStartPagePref()
    }

    private fun refreshStartPagePref() {
      val pref = findPreference<Preference>(START_PAGE_KEY) ?: return
      val selected = getSelectedStartPageOption()
      pref.summary = selected.title
    }

    private fun showStartPageDialog() {
      val context = requireContext()
      val options = getStartPageOptions()
      val selectedUrl = getSelectedStartPageOption().url
      val container =
        LinearLayout(context).apply {
          orientation = LinearLayout.VERTICAL
          val horizontalPadding = (20 * resources.displayMetrics.density).toInt()
          val verticalPadding = (8 * resources.displayMetrics.density).toInt()
          setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
        }

      val dialog =
        AlertDialog
          .Builder(context)
          .setTitle(R.string.pref_key_start_page)
          .setView(container)
          .setNegativeButton(R.string.btn_cancel, null)
          .create()

      options.forEach { option ->
        val row =
          createStartPageOptionView(option, option.url == selectedUrl) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
              putString(START_PAGE_KEY, option.url)
            }
            refreshStartPagePref()
            dialog.dismiss()
          }
        container.addView(row)
      }

      dialog.show()
    }

    private fun createStartPageOptionView(
      option: StartPageOption,
      checked: Boolean,
      onClick: () -> Unit,
    ): LinearLayout {
      val density = resources.displayMetrics.density
      val verticalPadding = (12 * density).toInt()
      val row =
        LinearLayout(requireContext()).apply {
          orientation = LinearLayout.HORIZONTAL
          setPadding(0, verticalPadding, 0, verticalPadding)
          isClickable = true
          isFocusable = true
          setOnClickListener { onClick() }
        }

      row.addView(
        RadioButton(requireContext()).apply {
          isChecked = checked
          isClickable = false
        },
      )

      val textContainer =
        LinearLayout(requireContext()).apply {
          orientation = LinearLayout.VERTICAL
          setPadding((8 * density).toInt(), 0, 0, 0)
        }

      textContainer.addView(
        TextView(requireContext()).apply {
          text = option.title
          textSize = 16f
        },
      )
      textContainer.addView(
        TextView(requireContext()).apply {
          text = option.url
          textSize = 13f
          alpha = 0.7f
        },
      )

      row.addView(
        textContainer,
        LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT,
        ),
      )
      return row
    }

    private fun getSelectedStartPageOption(): StartPageOption {
      val options = getStartPageOptions()
      val selectedUrl =
        PreferenceManager.getDefaultSharedPreferences(requireContext()).getString(START_PAGE_KEY, START_PAGE_MYBOOK_URL) ?: START_PAGE_MYBOOK_URL
      return options.firstOrNull { it.url == selectedUrl } ?: options.first { it.url == START_PAGE_MYBOOK_URL }
    }

    private fun getStartPageOptions(): List<StartPageOption> =
      listOf(
        StartPageOption(getString(R.string.start_page_home_title), START_PAGE_HOME_URL),
        StartPageOption(getString(R.string.start_page_mybook_title), START_PAGE_MYBOOK_URL),
        StartPageOption(getString(R.string.start_page_last_view_title), START_PAGE_LAST_VIEW_URL),
      )

    private data class StartPageOption(
      val title: String,
      val url: String,
    )

    // endregion

    // region 시스템 설정

    private fun setupLinkSettingsPref() {
      findPreference<Preference>("open_link_settings")?.setOnPreferenceClickListener {
        startActivity(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:${requireContext().packageName}".toUri()))
        true
      }
    }

    private fun refreshLinkSettingsPref() {
      val pref = findPreference<Preference>("open_link_settings") ?: return
      val approved = isLinkApproved()
      pref.isEnabled = !approved
      pref.summary =
        getString(
          if (approved) {
            R.string.pref_desc_open_link_settings_enabled
          } else {
            R.string.pref_desc_open_link_settings_disabled
          },
        )
    }

    private fun isLinkApproved(): Boolean =
      try {
        val manager = requireContext().getSystemService(DomainVerificationManager::class.java)
        val state = manager.getDomainVerificationUserState(requireContext().packageName)?.hostToStateMap?.get("novelpia.com")
        state == DomainVerificationUserState.DOMAIN_STATE_SELECTED || state == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
      } catch (_: Exception) {
        false
      }

    // endregion

    // region 뷰어

    // 뷰어 설정은 XML ListPreference 기본 동작만 사용한다.

    // endregion

    // region 광고 차단

    private fun setupFilterPrefs() {
      findPreference<Preference>(FilterPreferences.KEY_ENABLED)?.setOnPreferenceChangeListener { _, _ ->
        lifecycleScope.launch {
          withContext(Dispatchers.IO) {
            FilterRuntime.getInstance(requireContext()).refreshEngine(force = true)
          }
          refreshFilterPrefs()
        }
        true
      }

      findPreference<Preference>(FilterPreferences.KEY_SUBSCRIPTIONS)?.setOnPreferenceClickListener {
        showSubscriptionEditor()
        true
      }

      findPreference<Preference>(FilterPreferences.KEY_USER_RULES)?.setOnPreferenceClickListener {
        showMultilineEditor(
          title = getString(R.string.title_filters_user_rules),
          initialValue = FilterPreferences.getUserRuleEditorText(requireContext()),
        ) { value ->
          FilterPreferences.setUserRulesEditorText(requireContext(), value)
          lifecycleScope.launch {
            runCatching {
              withContext(Dispatchers.IO) {
                FilterRuntime.getInstance(requireContext()).refreshEngine(force = true)
              }
            }
            refreshFilterPrefs()
          }
          Toast.makeText(requireContext(), R.string.msg_filters_saved, Toast.LENGTH_SHORT).show()
        }
        true
      }

      findPreference<Preference>("filters_update_now")?.setOnPreferenceClickListener {
        lifecycleScope.launch {
          val summary =
            withContext(Dispatchers.IO) {
              FilterRuntime.getInstance(requireContext()).updateSubscriptions(force = true)
            }
          runCatching {
            withContext(Dispatchers.IO) {
              FilterRuntime.getInstance(requireContext()).refreshEngine(force = true)
            }
          }
          refreshFilterPrefs()
          Toast
            .makeText(
              requireContext(),
              if (summary.failedCount == 0) R.string.msg_filters_updated else R.string.msg_filters_update_failed,
              Toast.LENGTH_SHORT,
            ).show()
        }
        true
      }
    }

    private fun refreshFilterPrefs() {
      val context = requireContext()
      val urls = FilterPreferences.getSubscriptionUrls(context)
      val userRules = FilterPreferences.getUserRuleLines(context)
      val lastUpdated = FilterPreferences.getLastUpdatedAt(context)
      val lastError = FilterPreferences.getLastUpdateError(context)

      findPreference<Preference>(FilterPreferences.KEY_SUBSCRIPTIONS)?.summary =
        buildString {
          append(getString(R.string.pref_desc_filters_subscriptions))
          append("\n")
          append(if (urls.isEmpty()) "0 URL" else "${urls.size} URL")
        }

      findPreference<Preference>(FilterPreferences.KEY_USER_RULES)?.summary =
        buildString {
          append(getString(R.string.pref_desc_filters_user_rules))
          append("\n")
          append(if (userRules.isEmpty()) "0 rules" else "${userRules.size} rules")
        }

      findPreference<Preference>("filters_update_now")?.summary =
        when {
          lastError != null -> "${getString(R.string.pref_desc_filters_update_now)}\n$lastError"
          lastUpdated > 0L -> "${getString(R.string.pref_desc_filters_update_now)}\n${
            java.text.DateFormat.getDateTimeInstance().format(Date(lastUpdated))
          }"

          else -> getString(R.string.pref_desc_filters_update_now)
        }
    }

    private fun showSubscriptionEditor() {
      showMultilineEditor(
        title = getString(R.string.title_filters_subscriptions),
        initialValue = FilterPreferences.getSubscriptionEditorText(requireContext()),
        neutralButtonText = getString(R.string.btn_reset),
        onNeutralClick = { showResetSubscriptionConfirmation() },
      ) { value ->
        FilterPreferences.setSubscriptionEditorText(requireContext(), value)
        refreshFiltersAfterSubscriptionChange()
        Toast.makeText(requireContext(), R.string.msg_filters_saved, Toast.LENGTH_SHORT).show()
      }
    }

    private fun showResetSubscriptionConfirmation() {
      AlertDialog
        .Builder(requireContext())
        .setTitle(R.string.title_reset_filter_subscriptions)
        .setMessage(R.string.msg_reset_filter_subscriptions_warning)
        .setPositiveButton(R.string.btn_reset) { _, _ ->
          FilterPreferences.resetSubscriptionUrls(requireContext())
          refreshFiltersAfterSubscriptionChange()
          Toast.makeText(requireContext(), R.string.msg_filter_subscriptions_reset, Toast.LENGTH_SHORT).show()
        }.setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    private fun refreshFiltersAfterSubscriptionChange() {
      lifecycleScope.launch {
        runCatching {
          withContext(Dispatchers.IO) {
            FilterRuntime.getInstance(requireContext()).updateSubscriptions(force = true)
            FilterRuntime.getInstance(requireContext()).refreshEngine(force = true)
          }
        }
        refreshFilterPrefs()
      }
    }

    private fun showMultilineEditor(
      title: String,
      initialValue: String,
      neutralButtonText: String? = null,
      onNeutralClick: (() -> Unit)? = null,
      onSave: (String) -> Unit,
    ) {
      val editText =
        EditText(requireContext()).apply {
          setText(initialValue)
          minLines = 8
          gravity = android.view.Gravity.TOP or android.view.Gravity.START
          inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
      val container =
        LinearLayout(requireContext()).apply {
          val padding = (24 * resources.displayMetrics.density).toInt()
          setPadding(padding, padding / 2, padding, 0)
          addView(
            editText,
            LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT,
              LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
          )
        }

      val builder =
        AlertDialog
          .Builder(requireContext())
          .setTitle(title)
          .setView(container)
          .setPositiveButton(android.R.string.ok) { _, _ -> onSave(editText.text.toString()) }
          .setNegativeButton(R.string.btn_cancel, null)
      if (neutralButtonText != null && onNeutralClick != null) {
        builder.setNeutralButton(neutralButtonText) { _, _ -> onNeutralClick() }
      }
      builder.show()
    }

    // endregion

    // region 설정 백업

    private fun setupSettingBackupPrefs() {
      findPreference<Preference>("export_settings")?.setOnPreferenceClickListener {
        exportSettingsLauncher.launch(createSettingBackupFileName())
        true
      }

      findPreference<Preference>("import_settings")?.setOnPreferenceClickListener {
        importSettingsLauncher.launch(arrayOf("application/json", "text/json", "text/*", "application/octet-stream"))
        true
      }
    }

    private fun exportSettings(uri: Uri) {
      runCatching {
        val context = requireContext()
        val exportedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())
        val json =
          SettingBackupCodec.export(
            context = context,
            appVersion = getCurrentAppVersion(),
            exportedAt = exportedAt,
            bookmarks = BookmarkRepository(context).getAll(),
          )
        context.contentResolver.openOutputStream(uri)?.use { output ->
          OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
            writer.write(json)
          }
        } ?: error("Output stream is null")
      }.onSuccess {
        Toast.makeText(requireContext(), R.string.msg_settings_exported, Toast.LENGTH_SHORT).show()
      }.onFailure {
        Toast.makeText(requireContext(), R.string.msg_settings_export_failed, Toast.LENGTH_SHORT).show()
      }
    }

    private fun importSettings(uri: Uri) {
      runCatching {
        val json =
          requireContext()
            .contentResolver
            .openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            ?: error("Input stream is null")
        Log.i(TAG, "Loaded settings backup file: $uri")
        SettingBackupCodec.parse(json)
      }.onSuccess { backup ->
        if (backup.exportedAt == null) {
          Log.w(TAG, "Loaded settings backup file has no exportedAt")
        }
        if (backup.appVersion == null) {
          Log.w(TAG, "Loaded settings backup file has no appVersion")
        }
        importValidatedSettings(backup)
      }.onFailure {
        Toast.makeText(requireContext(), R.string.msg_settings_import_failed, Toast.LENGTH_SHORT).show()
      }
    }

    private fun importValidatedSettings(backup: SettingBackup) {
      val context = requireContext()
      val repository = BookmarkRepository(context)
      val hasExistingBookmarks = repository.getAll().isNotEmpty()
      val shouldRefreshFilters = shouldRefreshFiltersAfterImport(backup.settings)

      applyBackupSettings(backup.settings)
      refreshImportedSettingsUi()
      if (shouldRefreshFilters) refreshFiltersAfterImport()

      if (hasExistingBookmarks) {
        showBookmarkOverwriteDialog(repository, backup)
      } else {
        repository.saveAll(backup.bookmarks)
        Toast.makeText(context, R.string.msg_settings_imported, Toast.LENGTH_SHORT).show()
      }
    }

    private fun showBookmarkOverwriteDialog(
      repository: BookmarkRepository,
      backup: SettingBackup,
    ) {
      AlertDialog
        .Builder(requireContext())
        .setTitle(R.string.title_import_settings_bookmark_overwrite)
        .setMessage(R.string.msg_import_settings_bookmark_overwrite)
        .setPositiveButton(R.string.btn_overwrite) { _, _ ->
          repository.saveAll(backup.bookmarks)
          Toast.makeText(requireContext(), R.string.msg_settings_imported, Toast.LENGTH_SHORT).show()
        }.setNegativeButton(R.string.btn_skip) { _, _ ->
          Toast.makeText(requireContext(), R.string.msg_settings_imported_without_bookmarks, Toast.LENGTH_SHORT).show()
        }.show()
    }

    private fun applyBackupSettings(settings: BackupSettings) {
      PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
        putString(START_PAGE_KEY, SettingBackupCodec.startPageKeyToUrl(settings.startPage))
        putString("volume_behavior", settings.volumeBehavior)
        putString("volume_direction", settings.volumeDirection)
        putString("swipe_fraction", settings.swipeFraction)
        putString(TtsPreferences.KEY_CHUNK_MODE, settings.ttsChunkMode)
        putBoolean(FilterPreferences.KEY_ENABLED, settings.filtersEnabled)
        putBoolean(FilterPreferences.KEY_AUTO_UPDATE, settings.filtersAutoUpdate)
        putBoolean("auto_check_update", settings.autoCheckUpdate)
      }
      FilterPreferences.setSubscriptionUrls(requireContext(), settings.filterSubscriptions)
      FilterPreferences.setUserRuleLines(requireContext(), settings.filterUserRules)
    }

    private fun shouldRefreshFiltersAfterImport(settings: BackupSettings): Boolean {
      val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
      return prefs.getBoolean(FilterPreferences.KEY_ENABLED, true) != settings.filtersEnabled ||
        prefs.getBoolean(
          FilterPreferences.KEY_AUTO_UPDATE,
          true,
        ) != settings.filtersAutoUpdate ||
        FilterPreferences.getSubscriptionUrls(requireContext()) != settings.filterSubscriptions ||
        FilterPreferences.getUserRuleLines(requireContext()) != settings.filterUserRules
    }

    private fun refreshImportedSettingsUi() {
      val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
      listOf(
        "volume_behavior" to "move_page",
        "volume_direction" to "up_prev",
        "swipe_fraction" to "0.15",
        TtsPreferences.KEY_CHUNK_MODE to TtsPreferences.DEFAULT_CHUNK_MODE,
      ).forEach { (key, defaultValue) ->
        findPreference<ListPreference>(key)?.value = prefs.getString(key, defaultValue)
      }
      refreshStartPagePref()
      refreshFilterPrefs()
      refreshUpdatePrefs()
    }

    private fun refreshFiltersAfterImport() {
      lifecycleScope.launch {
        runCatching {
          withContext(Dispatchers.IO) {
            FilterRuntime.getInstance(requireContext()).refreshEngine(force = true)
          }
        }
        refreshFilterPrefs()
      }
    }

    private fun createSettingBackupFileName(): String {
      val timestamp = SimpleDateFormat("yyMMddHHmmss", Locale.US).format(Date())
      return "NovelRegEx-setting-$timestamp.json"
    }

    private fun getCurrentAppVersion(): String =
      requireContext()
        .packageManager
        .getPackageInfo(requireContext().packageName, 0)
        .versionName
        .orEmpty()

    // endregion

    // region 데이터

    private fun setupDataPrefs() {
      findPreference<Preference>("clear_cache")?.setOnPreferenceClickListener {
        lifecycleScope.launch {
          withContext(Dispatchers.IO) { requireContext().cacheDir.deleteRecursively() }
          Toast.makeText(context, R.string.msg_clear_cache, Toast.LENGTH_SHORT).show()
          refreshCacheSummary()
        }
        true
      }

      findPreference<Preference>("clear_webstorage")?.setOnPreferenceClickListener {
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(context, R.string.msg_clear_webstorage, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch { refreshWebStorageSummary() }
        true
      }

      findPreference<Preference>("clear_cookie")?.setOnPreferenceClickListener {
        AlertDialog
          .Builder(requireContext())
          .setTitle(R.string.title_clear_cookie)
          .setMessage(R.string.msg_clear_cookie_warning)
          .setPositiveButton(R.string.btn_delete) { _, _ ->
            CookieManager.getInstance().apply {
              removeAllCookies(null)
              flush()
            }
            Toast.makeText(context, R.string.msg_clear_cookie, Toast.LENGTH_SHORT).show()
            findPreference<Preference>("clear_cookie")?.summary = "${getString(R.string.pref_desc_clear_cookie)}\n0 B"
          }.setNegativeButton(R.string.btn_cancel, null)
          .show()
        true
      }
    }

    private suspend fun refreshStorageSummaries() {
      coroutineScope {
        launch { refreshCacheSummary() }
        launch { refreshWebStorageSummary() }
        launch { refreshCookieSummary() }
      }
    }

    private suspend fun refreshCacheSummary() {
      val size =
        withContext(Dispatchers.IO) {
          requireContext()
            .cacheDir
            .walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        }
      findPreference<Preference>("clear_cache")?.summary = "${getString(R.string.pref_desc_clear_cache)}\n${formatSize(size)}"
    }

    private suspend fun refreshWebStorageSummary() {
      val size =
        suspendCancellableCoroutine { cont ->
          WebStorage.getInstance().getOrigins { origins ->
            cont.resume(origins?.values?.filterIsInstance<WebStorage.Origin>()?.sumOf { it.usage } ?: 0L)
          }
        }
      findPreference<Preference>("clear_webstorage")?.summary = "${getString(R.string.pref_desc_clear_webstorage)}\n${formatSize(size)}"
    }

    private suspend fun refreshCookieSummary() {
      val size =
        withContext(Dispatchers.IO) {
          val cookieFile = File(requireContext().dataDir, "app_webview/Default/Cookies")
          if (cookieFile.exists()) cookieFile.length() else 0L
        }
      findPreference<Preference>("clear_cookie")?.summary = "${getString(R.string.pref_desc_clear_cookie)}\n${formatSize(size)}"
    }

    private fun formatSize(bytes: Long): String =
      when {
        bytes <= 0L -> "0 B"
        bytes < 1_024L -> "$bytes B"
        bytes < 1_048_576L -> "${bytes / 1_024} KB"
        else -> "%.1f MB".format(bytes / 1_048_576.0)
      }

    // endregion

    // region 업데이트

    private fun setupVersionPref() {
      findPreference<Preference>("current_version")?.summary = getCurrentAppVersion()
    }

    private fun setupUpdatePrefs() {
      findPreference<Preference>("check_update")?.setOnPreferenceClickListener {
        requestNotificationPermissionThen { lifecycleScope.launch { forceCheckUpdate() } }
        true
      }

      findPreference<Preference>("download_update")?.setOnPreferenceClickListener {
        val apkPath = UpdateChecker.prefs(requireContext()).getString(UpdateChecker.KEY_APK_PATH, null)
        if (apkPath != null && File(apkPath).exists()) {
          triggerInstall(apkPath)
        } else {
          requestNotificationPermissionThen { triggerDownload() }
        }
        true
      }
    }

    private fun refreshUpdatePrefs() {
      if (!isAdded) return
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return
      val context = requireContext()

      // 이미 설치된 버전의 APK 캐시 제거
      UpdateChecker.purgeInstalledApk(context)

      // 다운로드 진행 중이면 UI 비활성화
      if (UpdateChecker.isDownloadActive(context)) {
        checkPref.isEnabled = false
        checkPref.summary = getString(R.string.pref_desc_update_downloading)
        downloadPref.isEnabled = false
        return
      }

      // APK가 다운로드되어 설치 대기 중인 경우
      if (UpdateChecker.isApkReady(context)) {
        val readyVersion = UpdateChecker.prefs(context).getString(UpdateChecker.KEY_APK_VERSION, "")
        checkPref.isEnabled = true
        checkPref.summary = getString(R.string.pref_desc_check_update_available, readyVersion)
        downloadPref.title = getString(R.string.pref_key_install_update)
        downloadPref.summary = getString(R.string.pref_desc_apk_downloaded, readyVersion)
        downloadPref.isEnabled = true
        return
      }

      // 캐시된 업데이트 정보 확인
      if (UpdateChecker.hasFreshCache(context)) {
        val cached = UpdateChecker.getCachedInfo(context)
        pendingUpdateInfo = cached
        applyUpdateResult(cached)
      } else {
        checkPref.isEnabled = true
        checkPref.summary = getString(R.string.pref_desc_check_update_default)
        downloadPref.isEnabled = false
        downloadPref.title = getString(R.string.pref_key_download_update)
        downloadPref.summary = null
      }
    }

    private suspend fun forceCheckUpdate() {
      if (!isAdded) return
      val context = requireContext()

      // 기존 다운로드 및 APK 정리
      UpdateChecker.cancelDownload(context)
      UpdateChecker.cleanupApk(context)

      setCheckingState()

      val result = UpdateChecker.fetchLatest(context)
      UpdateChecker.saveCache(context, result)
      if (!isAdded) return

      val info = (result as? UpdateResult.Available)?.info
      pendingUpdateInfo = info
      applyUpdateResult(info, isError = result is UpdateResult.Error)
    }

    private fun setCheckingState() {
      findPreference<Preference>("check_update")?.apply {
        isEnabled = false
        summary = getString(R.string.pref_desc_check_update_checking)
      }
      findPreference<Preference>("download_update")?.isEnabled = false
    }

    private fun applyUpdateResult(
      info: UpdateInfo?,
      isError: Boolean = false,
    ) {
      val checkPref = findPreference<Preference>("check_update") ?: return
      val downloadPref = findPreference<Preference>("download_update") ?: return
      checkPref.isEnabled = true
      checkPref.summary =
        when {
          info != null -> getString(R.string.pref_desc_check_update_available, info.version)
          isError -> getString(R.string.pref_desc_check_update_error)
          else -> getString(R.string.pref_desc_check_update_latest)
        }
      downloadPref.title = getString(R.string.pref_key_download_update)
      downloadPref.summary = null
      downloadPref.isEnabled = info != null
    }

    private fun triggerDownload() {
      val info = pendingUpdateInfo ?: return
      val context = requireContext()

      val downloadId = UpdateChecker.enqueueDownload(context, info.downloadUrl, info.version)
      if (downloadId == -1L) {
        Toast.makeText(context, "Download failed to start", Toast.LENGTH_LONG).show()
        return
      }

      findPreference<Preference>("check_update")?.apply {
        isEnabled = false
        summary = getString(R.string.pref_desc_update_downloading)
      }

      findPreference<Preference>("download_update")?.isEnabled = false
    }

    private fun triggerInstall(apkPath: String) {
      val context = requireContext()
      val apkFile = File(apkPath)
      val installIntent = UpdateChecker.createInstallIntent(context, apkFile)

      // 프레퍼런스만 정리 — APK 파일은 설치 프로세스에서 사용하므로 유지
      UpdateChecker.prefs(context).edit {
        remove(UpdateChecker.KEY_APK_PATH)
        remove(UpdateChecker.KEY_APK_VERSION)
      }

      findPreference<Preference>("download_update")?.apply {
        title = getString(R.string.pref_key_download_update)
        summary = null
        isEnabled = false
      }
      startActivity(installIntent)
    }

    /** POST_NOTIFICATIONS 권한을 확인하고 필요 시 요청한 후 [action] 실행 */
    private fun requestNotificationPermissionThen(action: () -> Unit) {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
          requireContext(),
          POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
      ) {
        action()
        return
      }
      pendingPermissionAction = action
      showNotificationRationaleDialog()
    }

    private fun showNotificationRationaleDialog() {
      AlertDialog
        .Builder(requireContext())
        .setTitle(R.string.dlg_notification_permission_title)
        .setMessage(R.string.dlg_notification_permission_message)
        .setPositiveButton(R.string.btn_allow) { _, _ ->
          notificationPermissionLauncher.launch(POST_NOTIFICATIONS)
        }.setNegativeButton(R.string.btn_skip) { _, _ ->
          pendingPermissionAction?.invoke()
          pendingPermissionAction = null
        }.show()
    }

    private fun showGoToSettingsDialog() {
      AlertDialog
        .Builder(requireContext())
        .setTitle(R.string.dlg_notification_settings_title)
        .setMessage(R.string.dlg_notification_settings_message)
        .setPositiveButton(R.string.btn_go_to_settings) { _, _ ->
          startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
              data = Uri.fromParts("package", requireContext().packageName, null)
            },
          )
        }.setNegativeButton(R.string.btn_cancel, null)
        .show()
    }

    // endregion
  }
}
