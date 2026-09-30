package my.noveldokusha.features.reader.video

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Foreground-сервис media3: докачивает видео-эпизоды в фоне.
 *
 * FGS-тип dataSync — по официальной доке media3 (verify-point Task 14);
 * пермиッション FOREGROUND_SERVICE_DATA_SYNC уже объявлен в app-манифесте.
 * getScheduler() = null: без JobScheduler-рестартов, сервис живёт, пока
 * есть незавершённые загрузки (без intent-filter RESTART — он нужен только
 * для Scheduler). Канал уведомлений создаёт сам DownloadService onCreate
 * из ресурсных id ниже (локализация — из AAR media3).
 */
@UnstableApi
class VideoDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    0L,
    CHANNEL_ID,
    androidx.media3.exoplayer.R.string.exo_download_notification_channel_name,
    androidx.media3.exoplayer.R.string.exo_download_description,
) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface VideoDownloadEntryPoint {
        fun videoDownloadManager(): VideoDownloadManager
    }

    private val videoDownloadManager: VideoDownloadManager
        get() = EntryPointAccessors
            .fromApplication(applicationContext, VideoDownloadEntryPoint::class.java)
            .videoDownloadManager()

    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = videoDownloadManager.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notificationId: Int,
    ): Notification {
        // Тап по уведомлению → штатный лаунчер приложения.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return notificationHelper.buildProgressNotification(
            this,
            // Второй аргумент — @DrawableRes smallIcon, а не id уведомления
            // (сверено по байткоду 1.11.1): id использует сам DownloadService.
            android.R.drawable.stat_sys_download,
            contentIntent,
            getString(androidx.media3.exoplayer.R.string.exo_download_downloading),
            downloads,
            0,
        )
    }

    companion object {
        private const val CHANNEL_ID = "video_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1401
    }
}
