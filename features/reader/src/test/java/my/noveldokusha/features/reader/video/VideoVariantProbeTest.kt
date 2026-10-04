package my.noveldokusha.features.reader.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Чистая логика probe без Android-вызовов: классификация HTTP-сбоев на
 * «доказанная мёртвость» (4xx-ответ либо DNS без хоста) и «сбой без
 * доказательств» (таймаут, обрыв, 5xx/429).
 */
class VideoVariantProbeTest {

    @Test
    fun `4xx except 408 and 429 proves deadness`() {
        listOf(403, 404, 410).forEach {
            assertTrue(it.toString(), VideoVariantProbe.isDeadHttpCode(it))
        }
        listOf(500, 503, 408, 429, 301).forEach {
            assertFalse(it.toString(), VideoVariantProbe.isDeadHttpCode(it))
        }
    }

    @Test
    fun `only unknown host proves deadness of a failure`() {
        assertTrue(VideoVariantProbe.isDeadFailure(UnknownHostException("new.anime-phoenix.workers.dev")))
        assertFalse(VideoVariantProbe.isDeadFailure(SocketTimeoutException("timeout")))
        // OkHttp может завернуть DNS в IOException с причиной — цепочку читаем.
        assertTrue(
            VideoVariantProbe.isDeadFailure(
                IOException("connect failed", UnknownHostException("gone.example")),
            ),
        )
    }
}
