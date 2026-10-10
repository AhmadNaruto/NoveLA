package my.noveldokusha.data

import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.isHttpsUrl
import timber.log.Timber
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.scraper.Scraper

/**
 * Докачивает обложки по хранимому URL.
 *
 * @return bookUrl книг, у которых после попытки нет локального валидного файла
 * обложки: пустой хранимый URL или битый сетевой URL после неудачного скачивания —
 * их нужно ре-резолвить у источника отдельным проходом. Книги с не-сетевым URL
 * (локальная/ручная обложка, `local://…`) в список не попадают.
 */
suspend fun backfillCovers(
    books: List<Book>,
    appFileResolver: AppFileResolver,
    coverRepository: CoverRepository,
    // scraper необязателен: вызывающие без зависимости от scraper модуля
    // получают прежний same-origin referer.
    scraper: Scraper? = null,
): List<String> {
    val failedUrls = mutableListOf<String>()
    for (book in books) {
        val remote = book.coverImageUrl
        if (remote.isBlank()) {
            // Пустой хранимый URL не пропускаем молча — он уходит на ре-резолв.
            failedUrls.add(book.url)
            continue
        }
        // Не-сетевой URL (local:// и т.п.) — локальная/ручная обложка, не трогаем.
        if (!remote.isHttpsUrl) continue
        val folderName = appFileResolver.getLocalBookFolderName(book.url)
        val coverFile = appFileResolver.getStorageBookCoverImageFile(folderName)
        val referer = scraper?.coverReferer(book.url)
        if (!coverRepository.ensureCover(coverFile, remote, referer = referer)) {
            Timber.w("Failed to download cover for ${book.url}")
            failedUrls.add(book.url)
        }
    }
    return failedUrls
}
