package my.noveldokusha.features.reader.video

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Контракт бейджа (Task 14): состояния media3 → статусы UI
 * и целостность заголовков между enqueue и загрузчиком.
 */
class VideoDownloadStateTest {

    @Test
    fun `media3 states map to badge states`() {
        assertEquals(DownloadState.QUEUED, downloadStateOf(Download.STATE_QUEUED))
        assertEquals(DownloadState.QUEUED, downloadStateOf(Download.STATE_STOPPED))
        assertEquals(DownloadState.RUNNING, downloadStateOf(Download.STATE_DOWNLOADING))
        assertEquals(DownloadState.RUNNING, downloadStateOf(Download.STATE_RESTARTING))
        assertEquals(DownloadState.COMPLETED, downloadStateOf(Download.STATE_COMPLETED))
        assertEquals(DownloadState.FAILED, downloadStateOf(Download.STATE_FAILED))
        assertEquals(DownloadState.NONE, downloadStateOf(Download.STATE_REMOVING))
    }

    @Test
    fun `headers survive encode-decode roundtrip including colons`() {
        val headers = mapOf(
            "Referer" to "https://a.example/path:with/colon",
            "User-Agent" to "Mozilla/5.0",
        )
        assertEquals(headers, decodeHeaders(encodeHeaders(headers)))
    }

    @Test
    fun `missing payload decodes to empty headers`() {
        assertEquals(emptyMap<String, String>(), decodeHeaders(null))
        assertEquals(emptyMap<String, String>(), decodeHeaders(ByteArray(0)))
    }
}
