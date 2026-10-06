package my.noveldokusha.features.reader.video

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Контракт бейджа (Task 14): состояния media3 → статусы UI
 * и целостность заголовков между enqueue и загрузчиком.
 */
class VideoDownloadStateTest {

    @Test
    fun `media3 states map to badge states`() {
        assertEquals(
            ChapterDownloadUiState.QUEUED,
            chapterDownloadUiOf(Download.STATE_QUEUED, Download.STOP_REASON_NONE, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.QUEUED,
            chapterDownloadUiOf(Download.STATE_STOPPED, Download.STOP_REASON_NONE, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.PAUSED,
            chapterDownloadUiOf(Download.STATE_STOPPED, STOP_REASON_PAUSED, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.DOWNLOADING,
            chapterDownloadUiOf(Download.STATE_DOWNLOADING, Download.STOP_REASON_NONE, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.DOWNLOADING,
            chapterDownloadUiOf(Download.STATE_RESTARTING, Download.STOP_REASON_NONE, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.COMPLETED,
            chapterDownloadUiOf(Download.STATE_COMPLETED, Download.STOP_REASON_NONE, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.FAILED,
            chapterDownloadUiOf(Download.STATE_FAILED, Download.STOP_REASON_NONE, -1f).state,
        )
        assertEquals(
            ChapterDownloadUiState.NONE,
            chapterDownloadUiOf(Download.STATE_REMOVING, Download.STOP_REASON_NONE, -1f).state,
        )
    }

    @Test
    fun `progress is normalized and only for active downloads`() {
        assertEquals(
            0.5f,
            chapterDownloadUiOf(Download.STATE_DOWNLOADING, Download.STOP_REASON_NONE, 50f).progress,
        )
        // percentDownloaded == -1 (PERCENTAGE_UNSET) → индетерминированный прогресс
        assertNull(
            chapterDownloadUiOf(Download.STATE_DOWNLOADING, Download.STOP_REASON_NONE, -1f).progress
        )
        assertNull(
            chapterDownloadUiOf(Download.STATE_QUEUED, Download.STOP_REASON_NONE, 10f).progress
        )
        assertNull(
            chapterDownloadUiOf(Download.STATE_COMPLETED, Download.STOP_REASON_NONE, 100f).progress
        )
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
