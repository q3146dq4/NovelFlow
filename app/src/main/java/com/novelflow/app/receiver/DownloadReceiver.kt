package com.novelflow.app.receiver

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.novelflow.app.update.UpdateChecker
import com.novelflow.app.update.UpdateNotifier
import java.io.File

/** 다운로드 완료 이벤트를 수신하여 설치 알림 또는 실패 알림을 표시 */
class DownloadReceiver : BroadcastReceiver() {
  override fun onReceive(
    context: Context,
    intent: Intent,
  ) {
    if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return

    val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
    val prefs = UpdateChecker.prefs(context)
    val storedId = prefs.getLong(UpdateChecker.KEY_DOWNLOAD_ID, -1L)

    // 이 앱이 시작하지 않은 다운로드는 무시
    if (storedId == -1L || completedId != storedId) return

    // 중복 처리 방지를 위해 다운로드 ID 즉시 제거
    prefs.edit { remove(UpdateChecker.KEY_DOWNLOAD_ID) }

    val dm = context.getSystemService(DownloadManager::class.java)
    val cursor = dm.query(DownloadManager.Query().setFilterById(completedId))

    var status = DownloadManager.STATUS_FAILED
    var localUri: String? = null
    cursor?.use {
      if (it.moveToFirst()) {
        status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
      }
    }

    if (status == DownloadManager.STATUS_SUCCESSFUL && localUri != null) {
      handleSuccess(context, prefs, localUri)
    } else {
      handleFailure(context)
    }
  }

  private fun handleSuccess(
    context: Context,
    prefs: android.content.SharedPreferences,
    localUri: String,
  ) {
    val apkFile = File(localUri.toUri().path!!)
    if (!apkFile.exists()) return

    val apkVersion = UpdateChecker.getCachedInfo(context)?.version ?: "Unknown"
    prefs.edit {
      putString(UpdateChecker.KEY_APK_PATH, apkFile.absolutePath)
      putString(UpdateChecker.KEY_APK_VERSION, apkVersion)
    }
    UpdateNotifier.showInstallReady(context, apkFile)
  }

  private fun handleFailure(context: Context) {
    val cachedInfo = UpdateChecker.getCachedInfo(context)
    val downloadUrl = UpdateChecker.prefs(context).getString(UpdateChecker.KEY_PENDING_URL, null) ?: cachedInfo?.downloadUrl
    val version = cachedInfo?.version
    if (downloadUrl != null && version != null) {
      UpdateNotifier.showDownloadFailed(context, downloadUrl, version)
    }
  }
}
