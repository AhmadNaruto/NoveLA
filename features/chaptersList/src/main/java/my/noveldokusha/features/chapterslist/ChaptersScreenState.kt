package my.noveldokusha.features.chapterslist

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import my.noveldokusha.core.appPreferences.TernaryState
import my.noveldokusha.data.DownloadTaskState
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.features.reader.video.ChapterDownloadUi

internal data class ChaptersScreenState(
    val book: State<BookState>,
    val error: MutableState<String>,
    val selectedChaptersUrl: SnapshotStateMap<String, Unit>,
    val chapters: SnapshotStateList<ChapterWithContext>,
    val isRefreshing: MutableState<Boolean>,
    val sourceCatalogNameStrRes: State<Int?>,
    val settingChapterSort: MutableState<TernaryState>,
    val isLocalSource: State<Boolean>,
    val isRefreshable: State<Boolean>,
    val genres: MutableState<List<String>>,
    val rating: MutableState<String>,
    val status: MutableState<String>,
    val lastUpdateDate: MutableState<String>,
    val translatedChapterTitles: MutableState<Map<String, String>>,
    val chapterSizes: MutableState<Map<String, ChapterSize>>,
    val downloadTask: MutableState<DownloadTaskState?>,
    // Видео-книга (media3): «X» отмены в строке главы имеет смысл только у неё —
    // у текста/манги отмена снимает всю задачу книги, а не одну главу.
    val isVideoBook: State<Boolean>,
    // Статусы загрузок глав (видео media3 + текст/манга): chapterUrl → бейдж.
    val chapterDownloads: MutableState<Map<String, ChapterDownloadUi>>,
) {

    val isInSelectionMode = derivedStateOf { selectedChaptersUrl.size != 0 }

    data class BookState(
        val title: String,
        val url: String,
        val completed: Boolean = false,
        val lastReadChapter: String? = null,
        val inLibrary: Boolean = false,
        val coverImageUrl: String? = null,
        val description: String = "",
        val category: String = "",
        // Тип контента книги: "" — новелла (дефолт источников без content_type),
        // иначе "novel"/"manga"/"video".
        val contentType: String = "",
    ) {
        constructor(book: Book) : this(
            title = book.title,
            url = book.url,
            completed = book.completed,
            lastReadChapter = book.lastReadChapter,
            inLibrary = book.inLibrary,
            coverImageUrl = book.coverImageUrl,
            description = book.description,
            category = book.category,
            contentType = book.contentType,
        )
    }
}

/** Пара языков перевода, доступная для экспорта, с числом переведённых глав. */
data class LangPair(
    val sourceLang: String,
    val targetLang: String,
    val translatedChapters: Int,
)

/** Состояние диалога экспорта книги в EPUB. */
sealed interface ExportDialogState {
    data object Hidden : ExportDialogState

    /** Выбор контента для экспорта: оригинал или один из переводов. */
    data class ContentChoice(
        val bookUrl: String,
        val bookTitle: String,
        val totalChapters: Int,
        val downloadedChapters: Int,
        val availableTranslations: List<LangPair>,
        val exportDirectoryName: String?,
    ) : ExportDialogState

    /** Папка экспорта не выбрана — UI открывает SAF-пикер. */
    data object NeedDirectory : ExportDialogState
}