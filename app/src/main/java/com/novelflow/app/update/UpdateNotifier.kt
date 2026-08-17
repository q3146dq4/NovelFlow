package com.novelflow.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.novelflow.app.R
import com.novelflow.app.receiver.UpdateDownloadReceiver
import java.io.File

/** 업데이트 관련 알림을 통합 관리하는 객체 */
object UpdateNotifier {
  const val CHANNEL_ID = "update_channel"
  private const val ID_UPDATE_AVAILABLE = 1000
  private const val ID_INSTALL_READY = 1001
  private const val ID_DOWNLOAD_FAILED = 1002
  private const val REQUEST_UPDATE_DOWNLOAD = 100
  private const val REQUEST_DOWNLOAD_RETRY = 101

  /** 알림 채널 생성 — 앱 시작 시 한 번 호출 필요 */
  fun initChannel(context: Context) {
    val channel =
      NotificationChannel(
        CHANNEL_ID,
        context.getString(R.string.noti_channel_update),
        NotificationManager.IMPORTANCE_DEFAULT,
      ).apply {
        description = context.getString(R.string.noti_channel_update_desc)
        setSound(null, null)
      }
    context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
  }

  /** 업데이트 가능 알림 — 본문 탭은 무반응, 액션 버튼으로만 다운로드 시작 */
  fun showUpdateAvailable(
    context: Context,
    info: UpdateInfo,
  ) {
    val pending =
      PendingIntent.getBroadcast(
        context,
        REQUEST_UPDATE_DOWNLOAD,
        Intent(context, UpdateDownloadReceiver::class.java).apply {
          putExtra(UpdateDownloadReceiver.EXTRA_DOWNLOAD_URL, info.downloadUrl)
          putExtra(UpdateDownloadReceiver.EXTRA_VERSION, info.version)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    notify(context, ID_UPDATE_AVAILABLE) {
      setContentTitle(context.getString(R.string.noti_update_available_title))
      setContentText(context.getString(R.string.noti_update_available_text, info.version))
      addAction(0, context.getString(R.string.noti_action_download), pending)
      setAutoCancel(false)
    }
  }

  /** 설치 준비 완료 알림 — 탭하면 APK 설치 */
  fun showInstallReady(
    context: Context,
    apkFile: File,
  ) {
    val pending =
      PendingIntent.getActivity(
        context,
        0,
        UpdateChecker.createInstallIntent(context, apkFile),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    notify(context, ID_INSTALL_READY) {
      setContentTitle(context.getString(R.string.noti_update_ready_title))
      setContentText(context.getString(R.string.noti_update_ready_text))
      setContentIntent(pending)
      setAutoCancel(true)
    }
  }

  /** 다운로드 실패 알림 — 본문 탭은 무반응, 액션 버튼으로만 재시도 */
  fun showDownloadFailed(
    context: Context,
    downloadUrl: String,
    version: String,
  ) {
    val pending =
      PendingIntent.getBroadcast(
        context,
        REQUEST_DOWNLOAD_RETRY,
        Intent(context, UpdateDownloadReceiver::class.java).apply {
          putExtra(UpdateDownloadReceiver.EXTRA_DOWNLOAD_URL, downloadUrl)
          putExtra(UpdateDownloadReceiver.EXTRA_VERSION, version)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
    notify(context, ID_DOWNLOAD_FAILED) {
      setContentTitle(context.getString(R.string.noti_download_failed_title))
      setContentText(context.getString(R.string.noti_download_failed_text))
      addAction(0, context.getString(R.string.noti_action_retry), pending)
      setAutoCancel(false)
    }
  }

  /** 공통 알림 빌더 — 반복되는 설정을 한 곳에서 관리 */
  private inline fun notify(
    context: Context,
    id: Int,
    block: NotificationCompat.Builder.() -> Unit,
  ) {
    val notification =
      NotificationCompat
        .Builder(context, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .apply(block)
        .build()
    context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
  }
}
