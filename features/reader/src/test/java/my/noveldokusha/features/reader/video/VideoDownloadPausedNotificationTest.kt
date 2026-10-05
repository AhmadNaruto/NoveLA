package my.noveldokusha.features.reader.video

import androidx.media3.exoplayer.offline.Download
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Контракт уведомления «на паузе» (Task 14): оно заменяет FGS-уведомление,
 * которое media3 гасит вместе с сервисом после паузы всех заявок.
 */
class VideoDownloadPausedNotificationTest {

    @Test
    fun `all stopped shows paused notification`() {
        assertEquals(
            true,
            needsPausedNotification(listOf(Download.STATE_STOPPED, Download.STATE_STOPPED)),
        )
    }

    @Test
    fun `stopped with downloading keeps fgs notification`() {
        assertEquals(
            false,
            needsPausedNotification(listOf(Download.STATE_STOPPED, Download.STATE_DOWNLOADING)),
        )
    }

    @Test
    fun `only queued does not show paused notification`() {
        assertEquals(false, needsPausedNotification(listOf(Download.STATE_QUEUED)))
    }

    @Test
    fun `empty list does not show paused notification`() {
        assertEquals(false, needsPausedNotification(emptyList()))
    }

    @Test
    fun `stopped with removing does not duplicate fgs notification`() {
        assertEquals(
            false,
            needsPausedNotification(listOf(Download.STATE_STOPPED, Download.STATE_REMOVING)),
        )
    }
}
