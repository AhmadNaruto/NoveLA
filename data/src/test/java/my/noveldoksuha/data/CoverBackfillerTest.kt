package my.noveldokusha.data

import kotlinx.coroutines.runBlocking
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.scraper.Scraper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Контракт backfillCovers: возвращает bookUrl книг, у которых после попытки нет
 * локального валидного файла обложки. Пустой хранимый URL уходит на ре-резолв,
 * а не-сетевой (local:// и т.п. — ручная/локальная обложка) — нет.
 */
class CoverBackfillerTest {

    private val appFileResolver = mock<AppFileResolver>()
    private val coverRepository = mock<CoverRepository>()
    private val scraper = mock<Scraper>()

    private fun book(url: String, coverImageUrl: String) =
        Book(title = "Book $url", url = url, coverImageUrl = coverImageUrl)

    @Test
    fun `backfillCovers returns books without local cover as losers`() { runBlocking {
        val downloaded = book("https://site/a", "https://cdn.example.com/a.jpg")
        val downloadFailed = book("https://site/b", "https://cdn.example.com/b.jpg")
        val blankUrl = book("https://site/c", "")
        // isHttpsUrl — это «сетевой URL» (http:// или https://), поэтому для
        // не-сетевого значения берём URL без схемы.
        val nonNetworkUrl = book("https://site/d", "cdn.example.com/d.jpg")
        // Ручная обложка: локальный маркер не уходит на ре-резолв у источника.
        val manualCover = book("https://site/e", "local://covers/e.jpg")

        whenever(appFileResolver.getLocalBookFolderName(any())).thenReturn("folder")
        whenever(appFileResolver.getStorageBookCoverImageFile(any())).thenReturn(File("cover.jpg"))
        // Общий стаб на все вызовы: без него suspend-мок вернул бы null вместо
        // Boolean, и упала бы сама проверка результата.
        whenever(coverRepository.ensureCover(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(false)
        // Файл обложки скачался только у первой книги.
        whenever(coverRepository.ensureCover(anyOrNull(), eq("https://cdn.example.com/a.jpg"), anyOrNull()))
            .thenReturn(true)

        val losers = backfillCovers(
            books = listOf(downloaded, downloadFailed, blankUrl, nonNetworkUrl, manualCover),
            appFileResolver = appFileResolver,
            coverRepository = coverRepository,
            scraper = scraper,
        )

        // Только пустой и битый сетевой URL идут на ре-резолв;
        // не-сетевые (ручная обложка) исключены.
        assertEquals(
            listOf("https://site/b", "https://site/c"),
            losers
        )
        // ensureCover вызывается только для книг с сетевым (http/https) хранимым URL.
        verify(coverRepository, times(2)).ensureCover(anyOrNull(), anyOrNull(), anyOrNull())
    }
    }
}
