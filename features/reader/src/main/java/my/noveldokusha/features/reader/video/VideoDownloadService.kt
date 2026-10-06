package my.noveldokusha.features.reader.video

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import my.noveldokusha.reader.R

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

        // Одно уведомление на сервис: кнопки глобальные (все заявки разом),
        // иначе Cancel удалял бы только одну из N.
        val activeUrls = downloads
            .filter {
                it.state == Download.STATE_QUEUED ||
                    it.state == Download.STATE_DOWNLOADING ||
                    it.state == Download.STATE_RESTARTING
            }
            .map { it.request.id }
        val hasActive = activeUrls.isNotEmpty()
        val hasPaused = downloads.any { it.state == Download.STATE_STOPPED }
        val hasAny = downloads.isNotEmpty()

        // Названия серий: какие именно качаются. Нет заголовков — contentText
        // не трогаем (у media3 своя строка не подставляется).
        val chaptersText = videoDownloadManager.activeChaptersText(activeUrls)

        // Средний процент по качающимся — та же арифметика, что у media3
        // DownloadNotificationHelper; без известных процентов — индетерминированно.
        val percents = downloads
            .filter {
                it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
            }
            .map { it.percentDownloaded }
            .filter { it >= 0f }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            // Канал CHANNEL_ID создаёт сам DownloadService в onCreate —
            // строим уведомление вручную, т.к. buildProgressNotification
            // в media3 1.11.1 не умеет addAction.
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(contentIntent)
            .setContentTitle(getString(androidx.media3.exoplayer.R.string.exo_download_downloading))
            .setProgress(100, if (percents.isEmpty()) 0 else percents.average().toInt(), percents.isEmpty())
            .setOngoing(true)
            .setShowWhen(false)

        chaptersText?.let { builder.setContentText(it) }

        if (hasActive) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.download_pause),
                broadcastPendingIntent(
                    VideoDownloadNotificationReceiver.ACTION_PAUSE_ALL,
                    REQUEST_CODE_PAUSE,
                )
            )
        }
        if (hasPaused) {
            builder.addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.download_resume),
                broadcastPendingIntent(
                    VideoDownloadNotificationReceiver.ACTION_RESUME_ALL,
                    REQUEST_CODE_RESUME,
                )
            )
        }
        if (hasAny) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.download_cancel),
                broadcastPendingIntent(
                    VideoDownloadNotificationReceiver.ACTION_CANCEL_ALL,
                    REQUEST_CODE_CANCEL,
                )
            )
        }

        return builder.build()
    }

    /** Явный компонент receiver'а: intent-filter для кнопок не нужен. */
    private fun broadcastPendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, VideoDownloadNotificationReceiver::class.java).apply {
                this.action = action
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL_ID = "video_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 1401

        // Request-коды PendingIntent: уникальны на действие, иначе Android
        // считает их одним и тем же интентом и подменяет extras.
        private const val REQUEST_CODE_PAUSE = 1
        private const val REQUEST_CODE_RESUME = 2
        private const val REQUEST_CODE_CANCEL = 3
    }
}
