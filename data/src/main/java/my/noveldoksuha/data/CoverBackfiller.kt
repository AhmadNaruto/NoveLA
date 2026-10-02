package my.noveldokusha.data

import my.noveldokusha.core.AppFileResolver
import my.noveldokusha.core.isHttpsUrl
import timber.log.Timber
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.scraper.Scraper

suspend fun backfillCovers(
    books: List<Book>,
    appFileResolver: AppFileResolver,
    coverRepository: CoverRepository,
    // scraper необязателен: вызывающие без зависимости от scraper модуля
    // получают прежний same-origin referer.
    scraper: Scraper? = null,
) {
    for (book in books) {
        val remote = book.coverImageUrl
        if (remote.isBlank() || !remote.isHttpsUrl) continue
        val folderName = appFileResolver.getLocalBookFolderName(book.url)
        val coverFile = appFileResolver.getStorageBookCoverImageFile(folderName)
        val referer = scraper?.coverReferer(book.url)
        if (!coverRepository.ensureCover(coverFile, remote, referer = referer)) {
            Timber.w("Failed to download cover for ${book.url}")
        }
    }
}
