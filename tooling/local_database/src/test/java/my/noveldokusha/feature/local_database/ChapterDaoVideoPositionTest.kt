package my.noveldokusha.feature.local_database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import my.noveldokusha.feature.local_database.tables.Chapter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ChapterDao.updateVideoPosition: позиция и длительность просмотра видео
 * пишутся только в видео-поля главы и не затрагивают manga-путь
 * (lastReadPosition/lastReadOffset остаются как были).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChapterDaoVideoPositionTest {

    private lateinit var db: AppRoomDatabase

    private val chapterFixture = Chapter(
        title = "Episode 1",
        url = "https://site/book/1/ep/1",
        bookUrl = "https://site/book/1",
        position = 0,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppRoomDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `updateVideoPosition writes position and duration`() = runBlocking {
        val dao = db.chapterDao()
        dao.insert(listOf(chapterFixture))
        dao.updateVideoPosition(chapterFixture.url, positionMs = 90_500, durationMs = 1_440_000)
        val ch = requireNotNull(dao.get(chapterFixture.url))
        assertEquals(90_500L, ch.videoPositionMs)
        assertEquals(1_440_000L, ch.videoDurationMs)
    }

    @Test
    fun `updateVideoPosition keeps lastReadPosition and lastReadOffset intact`() = runBlocking {
        val dao = db.chapterDao()
        dao.insert(listOf(chapterFixture.copy(lastReadPosition = 7, lastReadOffset = 11)))
        dao.updateVideoPosition(chapterFixture.url, positionMs = 1, durationMs = 2)
        val ch = requireNotNull(dao.get(chapterFixture.url))
        assertEquals(7, ch.lastReadPosition)
        assertEquals(11, ch.lastReadOffset)
        assertEquals(1L, ch.videoPositionMs)
        assertEquals(2L, ch.videoDurationMs)
    }
}
