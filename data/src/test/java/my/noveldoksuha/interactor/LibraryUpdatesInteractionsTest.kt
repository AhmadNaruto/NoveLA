package my.noveldokusha.interactor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import android.content.Context
import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.Response
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.BookChaptersRepository
import my.noveldokusha.data.CoverRepository
import my.noveldokusha.data.DownloaderRepository
import my.noveldokusha.data.LibraryBooksRepository
import my.noveldokusha.feature.local_database.DAOs.LibraryDao
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.scraper.Scraper
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Регресс: updateBook освежает рейтинг/статус/дату обновления при КАЖДОМ вызове,
 * даже если в базе эти поля уже заполнены (fill-once-обёртки сняты).
 *
 * Второй блок — персист свежего URL обложки в update-потоках и при backfill.
 */
class LibraryUpdatesInteractionsTest {

    private val appRepository = mock<AppRepository>()
    private val bookChaptersRepository = mock<BookChaptersRepository>()
    private val libraryBooksRepository = mock<LibraryBooksRepository>()
    private val downloaderRepository = mock<DownloaderRepository>()
    private val libraryDao = mock<LibraryDao>()
    private val coverRepository = mock<CoverRepository>()
    private val appFileResolver = mock<AppFileResolver>()
    private val scraper = mock<Scraper>()
    private val context = mock<Context>()

    private val interactions = LibraryUpdatesInteractions(
        appRepository = appRepository,
        downloaderRepository = downloaderRepository,
        libraryDao = libraryDao,
        coverRepository = coverRepository,
        appFileResolver = appFileResolver,
        scraper = scraper,
        context = context,
    )

    private val bookUrl = "https://example.com/book/123"

    private fun bookWithFilledMetadata() = Book(
        title = "Test Book",
        url = bookUrl,
        completed = false,
        inLibrary = true,
        coverImageUrl = "https://example.com/cover.jpg",
        description = "Description",
        genres = "Action",
        rating = "5.0",
        status = "Ongoing",
        lastUpdateDate = "2026-01-01",
    )

    private suspend fun stubSupportingCalls() {
        whenever(appRepository.bookChapters).thenReturn(bookChaptersRepository)
        whenever(appRepository.libraryBooks).thenReturn(libraryBooksRepository)
        // Список глав — пустой, чтобы стратегия обновления дошла до merge без данных.
        whenever(bookChaptersRepository.chapters(bookUrl)).thenReturn(emptyList())
        // Ковер: источник вернул null — syncCover не вызывается (нужен только чтобы пройти блок).
        whenever(downloaderRepository.bookCoverImageUrl(bookUrl)).thenReturn(Response.Success<String?>(null))
        // Главы: пустой список → легаси-путь завершается без новых глав.
        whenever(downloaderRepository.bookChaptersList(bookUrl)).thenReturn(Response.Success(emptyList()))
        // Хэш: null → хэш-скип и legacy-обновление хэша не срабатывают.
        whenever(downloaderRepository.bookChaptersListHash(bookUrl)).thenReturn(Response.Success<String?>(null))
        // Файл обложки: без этих стабов syncCover упал бы на моках,
        // ensureCover отдаёт false — качать файл не удалось.
        whenever(appFileResolver.getLocalBookFolderName(bookUrl)).thenReturn("folder")
        whenever(appFileResolver.getStorageBookCoverImageFile("folder")).thenReturn(File("cover.jpg"))
        whenever(coverRepository.ensureCover(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(false)
    }

    @Test
    fun `updateBook refreshes rating status and lastUpdateDate even when fields are populated`() = runBlocking {
        whenever(downloaderRepository.bookRating(bookUrl)).thenReturn(Response.Success<String?>("5.0"))
        whenever(downloaderRepository.bookStatus(bookUrl)).thenReturn(Response.Success<String?>("Ongoing"))
        whenever(downloaderRepository.bookLastUpdate(bookUrl)).thenReturn(Response.Success<String?>("2026-01-01"))
        stubSupportingCalls()

        interactions.updateSpecificBooks(
            books = listOf(bookWithFilledMetadata()),
            countingUpdating = MutableStateFlow(null),
            currentUpdating = MutableStateFlow(emptySet()),
            newUpdates = MutableStateFlow(emptySet()),
            failedUpdates = MutableStateFlow(emptySet()),
        )

        // Все три метаданных запрошены у источника при каждом апдейте...
        verify(downloaderRepository).bookRating(bookUrl)
        verify(downloaderRepository).bookStatus(bookUrl)
        verify(downloaderRepository).bookLastUpdate(bookUrl)
        // ...и записаны в базу, хотя поля уже были заполнены.
        verify(libraryDao).updateRating(bookUrl, "5.0")
        verify(libraryDao).updateStatus(bookUrl, "Ongoing")
        verify(libraryDao).updateLastUpdateDate(bookUrl, "2026-01-01")
    }

    /**
     * Свежий URL обложки, полученный от источника, обязан попасть в БД ДО попытки
     * скачать файл: сломанный URL старого источника — корень бага битых обложек.
     */
    @Test
    fun `updateBook persists fresh cover url even when cover download fails`() = runBlocking {
        val freshUrl = "https://cdn.example.com/fresh.jpg"
        stubSupportingCalls()
        whenever(downloaderRepository.bookCoverImageUrl(bookUrl)).thenReturn(Response.Success<String?>(freshUrl))

        interactions.updateSpecificBooks(
            books = listOf(bookWithFilledMetadata()),
            countingUpdating = MutableStateFlow(null),
            currentUpdating = MutableStateFlow(emptySet()),
            newUpdates = MutableStateFlow(emptySet()),
            failedUpdates = MutableStateFlow(emptySet()),
        )

        // ensureCover в stubSupportingCalls возвращает false — файла скачать не
        // удалось, но свежий URL всё равно персистится.
        verify(libraryBooksRepository).updateCover(bookUrl, freshUrl)
    }

    /**
     * Тот же контракт для одиночного обновления метаданных: свежий URL обложки
     * пишется в БД до попытки скачать файл.
     */
    @Test
    fun `updateSingleBookMetadata persists fresh cover url`() { runBlocking {
        val freshUrl = "https://cdn.example.com/fresh.jpg"
        stubSupportingCalls()
        whenever(libraryBooksRepository.get(bookUrl)).thenReturn(bookWithFilledMetadata())
        whenever(downloaderRepository.bookCoverImageUrl(bookUrl)).thenReturn(Response.Success<String?>(freshUrl))

        interactions.updateSingleBookMetadata(bookUrl)

        verify(libraryBooksRepository).updateCover(bookUrl, freshUrl)
    }
    }

    /**
     * Книга, у которой после backfill нет валидного локального файла (здесь — битый
     * хранимый URL), получает свежий URL у источника и сохраняет его в БД.
     */
    @Test
    fun `backfill re-resolves loser book and persists fresh cover url`() { runBlocking {
        val freshUrl = "https://cdn.example.com/fresh.jpg"
        val deadUrl = "https://old-source.example.com/dead-cover.jpg"
        val brokenBook = bookWithFilledMetadata().copy(coverImageUrl = deadUrl)

        stubSupportingCalls()
        whenever(libraryBooksRepository.getAllInLibrary()).thenReturn(listOf(brokenBook))
        whenever(downloaderRepository.bookCoverImageUrl(bookUrl)).thenReturn(Response.Success<String?>(freshUrl))

        interactions.backfillMissingCovers()

        // Свежий URL источника записан в БД...
        verify(libraryBooksRepository).updateCover(bookUrl, freshUrl)
        // ...а качался уже по нему, а не по мёртвому хранимому URL.
        verify(coverRepository).ensureCover(anyOrNull(), eq(freshUrl), anyOrNull())
    }
    }

    /**
     * Ре-резолв обложки у источника — однократно за сессию процесса: книга, у которой
     * источник не отдаёт обложку, не должна спрашивать свежий URL при каждом открытии
     * библиотеки.
     */
    @Test
    fun `backfill re-resolves each book only once per process session`() { runBlocking {
        val freshUrl = "https://cdn.example.com/fresh.jpg"
        val deadUrl = "https://old-source.example.com/dead-cover.jpg"
        val brokenBook = bookWithFilledMetadata().copy(coverImageUrl = deadUrl)

        stubSupportingCalls()
        whenever(libraryBooksRepository.getAllInLibrary()).thenReturn(listOf(brokenBook))
        whenever(downloaderRepository.bookCoverImageUrl(bookUrl)).thenReturn(Response.Success<String?>(freshUrl))

        interactions.backfillMissingCovers()
        interactions.backfillMissingCovers()

        verify(downloaderRepository, times(1)).bookCoverImageUrl(bookUrl)
    }
    }
}
