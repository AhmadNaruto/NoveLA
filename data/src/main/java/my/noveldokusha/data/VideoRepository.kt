package my.noveldokusha.data

import my.noveldokusha.core.Response
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.scraper.domain.VideoSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Резолв эпизода (chapterUrl) в видеопотоки. HTML-путь (ChapterBodyRepository)
 * для видео не используется — плагин сам делает http внутри getVideoList.
 *
 * Известные внутренние ошибки — типизированными исключениями: сообщение для
 * лога оставляем здесь (как в DownloaderRepository), а текст для UI собирает
 * экран из ресурсов (хардкод локали в data-слое запрещён).
 */

/** URL не подходит ни одному загруженному источнику. */
class VideoSourceNotFoundException(url: String) : IllegalStateException("No source for $url")

/** У источника не объявлена getVideoList. */
class VideoListNotDeclaredException : IllegalStateException("getVideoList not declared")

@Singleton
class VideoRepository @Inject constructor(
    private val scraper: Scraper,
) {
    suspend fun resolve(chapterUrl: String): Response<List<VideoSource>> {
        val source = scraper.getCompatibleSource(chapterUrl)
            ?: return Response.Error(
                "No source for $chapterUrl",
                VideoSourceNotFoundException(chapterUrl)
            )
        return source.getVideoList(chapterUrl)
            ?: Response.Error(
                "getVideoList not declared",
                VideoListNotDeclaredException()
            )
    }
}
