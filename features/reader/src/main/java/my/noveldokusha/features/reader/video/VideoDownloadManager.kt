package my.noveldokusha.features.reader.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.ExoDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import my.noveldokusha.core.OfflineVideoCleaner
import my.noveldokusha.network.MediaHttpClient
import my.noveldokusha.scraper.domain.VideoSource
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Статус media3-загрузки эпизода — для бейджа в списке глав. */
enum class DownloadState { NONE, QUEUED, RUNNING, COMPLETED, FAILED }

/**
 * Обёртка media3 [DownloadManager] для офлайн-видео (Task 14).
 *
 * Схема по официальной доке media3 (verify-point брифа): заявка
 * [DownloadRequest] уходит в [VideoDownloadService] через
 * [DownloadService.sendAddDownload] — сервис поднимает FGS и докачивает
 * в фоне; singleton здесь владеет кэшем и индексом.
 *
 * Кэш — в filesDir (D6): «Очистить кэш» не трогает скачанные эпизоды.
 */
@UnstableApi
@Singleton
class VideoDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @MediaHttpClient private val client: OkHttpClient,
) : OfflineVideoCleaner {
    private val databaseProvider = ExoDatabaseProvider(context)

    private val downloadCache = SimpleCache(
        File(context.filesDir, "video_downloads"),
        NoOpCacheEvictor(),
        databaseProvider,
    )

    /**
     * DownloadManager с явной фабрикой даунлоадеров: заголовки (Referer и пр.)
     * едут в DownloadRequest.data и применяются per-download — глобальных
     * default-заголовков у OkHttpDataSource.Factory для параллельных
     * загрузок из разных источников быть не может.
     */
    val downloadManager: DownloadManager = DownloadManager(
        context,
        DefaultDownloadIndex(databaseProvider),
        HeaderAwareDownloaderFactory(downloadCache, client),
    )

    /** Ставит эпизод в media3-очередь; id заявки = chapterUrl. */
    suspend fun enqueue(chapterUrl: String, video: VideoSource) = withContext(Dispatchers.IO) {
        val existing = runCatching { downloadManager.downloadIndex.getDownload(chapterUrl) }.getOrNull()
        if (existing != null && existing.state == Download.STATE_COMPLETED) return@withContext
        val builder = DownloadRequest.Builder(chapterUrl, Uri.parse(video.url))
        // Тип по расширению: HLS качает HlsDownloader (плейлист+сегменты),
        // иначе — один файл ProgressiveDownloader-ом.
        if (video.url.contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        if (video.headers.isNotEmpty()) builder.setData(encodeHeaders(video.headers))
        DownloadService.sendAddDownload(
            context, VideoDownloadService::class.java, builder.build(), true
        )
    }

    /** Наблюдение для бейджа: текущий статус из индекса, затем изменения. */
    fun observe(chapterUrl: String): Flow<DownloadState> = callbackFlow {
        val listener = object : DownloadManager.Listener {
            override fun onDownloadChanged(
                manager: DownloadManager,
                download: Download,
                exception: Exception?,
            ) {
                if (download.request.id == chapterUrl) trySend(downloadStateOf(download.state))
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                if (download.request.id == chapterUrl) trySend(DownloadState.NONE)
            }
        }
        downloadManager.addListener(listener)
        trySend(currentState(chapterUrl))
        awaitClose { downloadManager.removeListener(listener) }
        // Весь producer (addListener + синхронный disk-чтение в currentState)
        // уходит в IO: коллекторы на Main (combine в ChaptersViewModel).
    }.flowOn(Dispatchers.IO).distinctUntilChanged()

    /** Удаление: index + кэш-файлы снимает сам DownloadManager. */
    fun remove(chapterUrl: String) {
        downloadManager.removeDownload(chapterUrl)
    }

    /**
     * Полная очистка всех офлайн-видео. Тот же экземпляр менеджера
     * отдаёт [VideoDownloadService.getDownloadManager], поэтому снятие
     * идёт через общий DownloadManager (index + кэш-файлы), без отдельного
     * вызова сервиса. Удаление асинхронно — файлы подчищает DownloadManager.
     */
    override fun removeAll() {
        downloadManager.removeAllDownloads()
    }

    /**
     * Шаг 5: источник для полностью скачанного эпизода — без сети и без
     * заголовков. Плеер играет uri из заявки через общий [downloadCache]
     * (см. [playerDataSourceFactory]); file:// тут невозможен — HLS
     * скачивается набором файлов, единого локального URL у media3 нет.
     */
    suspend fun completedVideo(chapterUrl: String): VideoSource? = withContext(Dispatchers.IO) {
        val download = runCatching { downloadManager.downloadIndex.getDownload(chapterUrl) }
            .getOrNull() ?: return@withContext null
        if (download.state != Download.STATE_COMPLETED) return@withContext null
        VideoSource(url = download.request.uri.toString())
    }

    /**
     * Тот же кэш, что у загрузчика: скачанное читается, остальное — из сети.
     * Запись из плеера выключена (дока media3 downloading-media): онлайн-
     * воспроизведение не должно заливать байты в filesDir под NoOpCacheEvictor
     * (докачивает только DownloadService); промах читается через upstream.
     */
    fun playerDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory(null)

    private fun currentState(chapterUrl: String): DownloadState =
        runCatching { downloadManager.downloadIndex.getDownload(chapterUrl) }
            .getOrNull()?.let { downloadStateOf(it.state) } ?: DownloadState.NONE
}

/** Фабрика с заголовками конкретной заявки (см. [encodeHeaders]). */
@UnstableApi
private class HeaderAwareDownloaderFactory(
    private val cache: SimpleCache,
    private val client: OkHttpClient,
) : DownloaderFactory {
    override fun createDownloader(request: DownloadRequest): Downloader {
        val upstream = OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(decodeHeaders(request.data))
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
        val mediaItem = request.toMediaItem()
        return if (request.mimeType == MimeTypes.APPLICATION_M3U8) {
            HlsDownloader(mediaItem, dataSourceFactory)
        } else {
            ProgressiveDownloader(mediaItem, dataSourceFactory)
        }
    }
}

// ponytail: line-based кодировка заголовков вместо JSON — значения HTTP-заголовков
// не содержат переводов строк; если понадобится больше структуры — kotlinx.serialization.
internal fun encodeHeaders(headers: Map<String, String>): ByteArray =
    headers.entries.joinToString("\n") { "${it.key}:${it.value}" }.encodeToByteArray()

internal fun decodeHeaders(data: ByteArray?): Map<String, String> {
    if (data == null || data.isEmpty()) return emptyMap()
    return data.decodeToString().lineSequence()
        .mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
        }
        .toMap()
}

/** Маппинг состояний media3 в статусы бейджа. */
internal fun downloadStateOf(state: Int): DownloadState = when (state) {
    Download.STATE_QUEUED, Download.STATE_STOPPED -> DownloadState.QUEUED
    Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> DownloadState.RUNNING
    Download.STATE_COMPLETED -> DownloadState.COMPLETED
    Download.STATE_FAILED -> DownloadState.FAILED
    else -> DownloadState.NONE
}
