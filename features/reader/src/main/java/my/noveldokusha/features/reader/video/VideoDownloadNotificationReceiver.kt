package my.noveldokusha.features.reader.video

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Кнопки уведомлений видео-загрузок: глобальные действия над всеми заявками.
 *
 * Intent-filter не нужен — PendingIntent всегда указывает компонент явно.
 */
@UnstableApi
@AndroidEntryPoint
class VideoDownloadNotificationReceiver : BroadcastReceiver() {

    @Inject
    lateinit var videoDownloadManager: VideoDownloadManager

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PAUSE_ALL -> videoDownloadManager.pauseAll()
            ACTION_RESUME_ALL -> videoDownloadManager.resumeAll()
            ACTION_CANCEL_ALL -> videoDownloadManager.cancelAll()
            else -> return
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "my.noveldokusha.action.VIDEO_DOWNLOAD_PAUSE_ALL"
        const val ACTION_RESUME_ALL = "my.noveldokusha.action.VIDEO_DOWNLOAD_RESUME_ALL"
        const val ACTION_CANCEL_ALL = "my.noveldokusha.action.VIDEO_DOWNLOAD_CANCEL_ALL"
    }
}
