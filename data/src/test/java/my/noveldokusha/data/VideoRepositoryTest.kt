package my.noveldokusha.data

import kotlinx.coroutines.runBlocking
import my.noveldokusha.core.Response
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.domain.VideoSource
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class VideoRepositoryTest {

    @Test
    fun `resolve errors when no compatible source`() = runBlocking {
        val scraper = mock<Scraper>()
        whenever(scraper.getCompatibleSource("https://x/ep")).thenReturn(null)
        assertTrue(VideoRepository(scraper).resolve("https://x/ep") is Response.Error)
    }

    @Test
    fun `resolve errors when plugin does not declare getVideoList`() = runBlocking {
        // SourceInterface — sealed, Mockito его не мокает; как в других тестах
        // модуля, мокаем не-sealed подинтерфейс Catalog.
        val source = mock<SourceInterface.Catalog>()
        whenever(source.getVideoList("https://x/ep")).thenReturn(null)
        val scraper = mock<Scraper>()
        whenever(scraper.getCompatibleSource("https://x/ep")).thenReturn(source)
        assertTrue(VideoRepository(scraper).resolve("https://x/ep") is Response.Error)
    }

    @Test
    fun `resolve passes through success list`() = runBlocking {
        val expected = listOf(VideoSource(url = "https://cdn/x.m3u8"))
        val source = mock<SourceInterface.Catalog>()
        whenever(source.getVideoList("https://x/ep")).thenReturn(Response.Success(expected))
        val scraper = mock<Scraper>()
        whenever(scraper.getCompatibleSource("https://x/ep")).thenReturn(source)
        val r = VideoRepository(scraper).resolve("https://x/ep") as Response.Success
        assertEquals(expected, r.data)
    }
}
