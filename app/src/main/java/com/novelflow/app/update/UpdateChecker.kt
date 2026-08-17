package com.novelflow.app.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.novelflow.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 앱 업데이트의 확인·캐시·다운로드·설치를 중앙에서 관리하는 객체.
 *
 * 알림 표시는 [UpdateNotifier]에 위임하고,
 * 이 객체는 상태 관리와 비즈니스 로직에 집중한다.
 */
object UpdateChecker {
  // SharedPreferences 키 (리시버/액티비티에서도 참조)
  const val KEY_APK_PATH = "apk_path"
  const val KEY_APK_VERSION = "apk_version"
  const val KEY_DOWNLOAD_ID = "download_id"
  const val KEY_PENDING_URL = "pending_download_url"

  private const val API_URL = "https://api.github.com/repos/TetraTheta/np-viewer/releases/latest"
  private const val CACHE_TTL_MS = 3_600_000L // 1시간
  private const val KEY_CACHE_URL = "cache_download_url"
  private const val KEY_CACHE_VERSION = "cache_version"
  private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
  private const val KEY_LAST_VERSION = "last_known_version_name"
  private const val PREFS_NAME = "update_prefs"

  fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  // region 네트워크

  /** GitHub에서 최신 릴리스 정보를 가져와 현재 버전과 비교 */
  suspend fun fetchLatest(context: Context): UpdateResult =
    withContext(Dispatchers.IO) {
      try {
        val conn =
          (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10_000
            readTimeout = 10_000
          }

        if (conn.responseCode != 200) {
          conn.disconnect()
          return@withContext UpdateResult.Error
        }

        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        conn.disconnect()

        val tagName = json.getString("tag_name").removePrefix("v")
        val currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return@withContext UpdateResult.Error

        if (!isNewer(tagName, currentVersion)) return@withContext UpdateResult.UpToDate

        val downloadUrl =
          json.getJSONArray("assets").let { assets ->
            (0 until assets.length())
              .map { assets.getJSONObject(it) }
              .firstOrNull { it.getString("name").endsWith(".apk") }
              ?.getString("browser_download_url")
          } ?: return@withContext UpdateResult.Error

        UpdateResult.Available(UpdateInfo(tagName, downloadUrl))
      } catch (_: Exception) {
        UpdateResult.Error
      }
    }

  /** 최신 릴리스 확인 후 업데이트가 있으면 알림 표시 */
  suspend fun checkAndNotify(context: Context) {
    val result = fetchLatest(context)
    saveCache(context, result)
    if (result is UpdateResult.Available) {
      UpdateNotifier.showUpdateAvailable(context, result.info)
    }
  }

  // endregion

  // region 캐시

  fun hasFreshCache(context: Context): Boolean {
    val timestamp = prefs(context).getLong(KEY_CACHE_TIMESTAMP, -1L)
    return timestamp != -1L && System.currentTimeMillis() - timestamp <= CACHE_TTL_MS
  }

  fun getCachedInfo(context: Context): UpdateInfo? {
    val p = prefs(context)
    val version = p.getString(KEY_CACHE_VERSION, null)?.takeIf { it.isNotEmpty() } ?: return null
    val url = p.getString(KEY_CACHE_URL, null)?.takeIf { it.isNotEmpty() } ?: return null
    return UpdateInfo(version, url)
  }

  fun saveCache(
    context: Context,
    result: UpdateResult,
  ) {
    prefs(context).edit {
      putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
      when (result) {
        is UpdateResult.Available -> {
          putString(KEY_CACHE_VERSION, result.info.version)
          putString(KEY_CACHE_URL, result.info.downloadUrl)
          putString(KEY_PENDING_URL, result.info.downloadUrl)
        }

        is UpdateResult.UpToDate -> {
          putString(KEY_CACHE_VERSION, "")
          putString(KEY_CACHE_URL, "")
        }

        is UpdateResult.Error -> return // 에러는 캐시하지 않음
      }
    }
  }

  // endregion

  // region 다운로드

  /** DownloadManager를 통해 APK 다운로드 시작. 성공 시 다운로드 ID, 실패 시 -1 반환 */
  fun enqueueDownload(
    context: Context,
    downloadUrl: String,
    version: String,
  ): Long {
    val request =
      DownloadManager.Request(downloadUrl.toUri()).apply {
        setTitle(context.getString(R.string.noti_download_title))
        setDescription(context.getString(R.string.noti_download_desc, version))
        setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        setAllowedOverMetered(true)
        setAllowedOverRoaming(true)
        setDestinationInExternalFilesDir(context, null, "novelflow-$version.apk")
      }
    return try {
      val dm = context.getSystemService(DownloadManager::class.java)
      val downloadId = dm.enqueue(request)
      prefs(context).edit { putLong(KEY_DOWNLOAD_ID, downloadId) }
      downloadId
    } catch (_: Exception) {
      -1L
    }
  }

  /** 진행 중인 다운로드가 있는지 확인하고, 완료/실패 상태면 ID를 정리 */
  fun isDownloadActive(context: Context): Boolean {
    val prefs = prefs(context)
    val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
    if (downloadId == -1L) return false

    val dm = context.getSystemService(DownloadManager::class.java)
    val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId)) ?: return false
    val active =
      cursor.use {
        it.moveToFirst() &&
          it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)).let { status ->
            status == DownloadManager.STATUS_RUNNING || status == DownloadManager.STATUS_PENDING
          }
      }

    // 완료됐거나 존재하지 않는 다운로드 — 상태 정리
    if (!active) prefs.edit { remove(KEY_DOWNLOAD_ID) }
    return active
  }

  /** 진행 중인 다운로드 취소 및 상태 정리 */
  fun cancelDownload(context: Context) {
    val prefs = prefs(context)
    val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
    if (downloadId != -1L) {
      context.getSystemService(DownloadManager::class.java).remove(downloadId)
      prefs.edit { remove(KEY_DOWNLOAD_ID) }
    }
  }

  // endregion

  // region 설치

  /** APK 설치를 위한 ACTION_VIEW Intent 생성 */
  fun createInstallIntent(
    context: Context,
    apkFile: File,
  ): Intent {
    val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.provider", apkFile)
    return Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(apkUri, "application/vnd.android.package-archive")
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  /** 다운로드된 APK가 설치 준비 상태인지 확인 */
  fun isApkReady(context: Context): Boolean {
    val prefs = prefs(context)
    val path = prefs.getString(KEY_APK_PATH, null) ?: return false
    val version = prefs.getString(KEY_APK_VERSION, null) ?: return false
    return version.isNotEmpty() && File(path).exists()
  }

  // endregion

  // region 정리

  /** 캐시된 APK 파일 삭제 및 관련 설정 초기화 */
  fun cleanupApk(context: Context) {
    val prefs = prefs(context)
    prefs.getString(KEY_APK_PATH, null)?.let { File(it).delete() }
    prefs.edit {
      remove(KEY_APK_PATH)
      remove(KEY_APK_VERSION)
    }
  }

  /** 이미 설치된 버전의 APK 캐시 제거 */
  fun purgeInstalledApk(context: Context) {
    val prefs = prefs(context)
    val apkVersion = prefs.getString(KEY_APK_VERSION, null) ?: return
    val currentVersion =
      try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return
      } catch (_: Exception) {
        return
      }
    if (!isNewer(apkVersion, currentVersion)) cleanupApk(context)
  }

  /** 앱 업데이트 후 이전 다운로드 상태 전부 정리 */
  fun processAppUpdate(context: Context) {
    val prefs = prefs(context)
    val currentVersion =
      try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return
      } catch (_: Exception) {
        return
      }
    val lastVersion = prefs.getString(KEY_LAST_VERSION, null)
    if (lastVersion != null && isNewer(currentVersion, lastVersion)) {
      cleanupApk(context)
      prefs.edit {
        remove(KEY_DOWNLOAD_ID)
        remove(KEY_PENDING_URL)
        remove(KEY_CACHE_URL)
        remove(KEY_CACHE_VERSION)
        remove(KEY_CACHE_TIMESTAMP)
      }
    }
    prefs.edit { putString(KEY_LAST_VERSION, currentVersion) }
  }

  // endregion

  // region 버전 비교

  /** remote가 current보다 새 버전인지 비교 (semantic versioning) */
  fun isNewer(
    remote: String,
    current: String,
  ): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val c = current.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, c.size)) {
      val rv = r.getOrElse(i) { 0 }
      val cv = c.getOrElse(i) { 0 }
      if (rv != cv) return rv > cv
    }
    return false
  }

  // endregion
}
