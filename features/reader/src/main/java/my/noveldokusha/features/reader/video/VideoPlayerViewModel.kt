package my.noveldokusha.features.reader.video

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import my.noveldokusha.core.Response
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.data.BookChaptersRepository
import my.noveldokusha.data.VideoListNotDeclaredException
import my.noveldokusha.data.VideoRepository
import my.noveldokusha.data.VideoSourceNotFoundException
import my.noveldokusha.features.reader.ReaderRepository
import my.noveldokusha.reader.R
import my.noveldokusha.scraper.domain.VideoSource
import javax.inject.Inject

/**
 * Состояние экрана плеера: загрузка источников → готово/ошибка.
 * Три-состояния контракта VideoRepository: Success(empty) → ошибка
 * «источники не найдены» (текст из ресурса), Error → типизированное
 * исключение → ресурс, иначе свободный текст плагина,
 * Success(не пусто) → Ready.
 */
@HiltViewModel
// internal: конструктор принимает internal ReaderRepository (как MangaReaderViewModel).
internal class VideoPlayerViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val videoDownloadManager: VideoDownloadManager,
    private val bookChaptersRepository: BookChaptersRepository,
    private val readerRepository: ReaderRepository,
    private val appPreferences: AppPreferences,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        sealed interface Error : State {
            /** Свободный текст (сообщение плагина из Response.Error.message). */
            data class Message(val text: String) : Error
            /** Внутренняя ошибка: текст экран берёт из ресурса (локализация). */
            data class Localized(@StringRes val messageRes: Int) : Error
        }
        data class Ready(val videos: List<VideoSource>) : State
    }

    var bookUrl: String = checkNotNull(savedStateHandle["bookUrl"])
        private set

    /** Текущий эпизод; меняется при next/prev (switchTo). */
    var chapterUrl: String = checkNotNull(savedStateHandle["chapterUrl"])
        private set

    /** Сохранённая позиция просмотра (Chapter.videoPositionMs) — для seekTo. */
    var startPositionMs: Long = 0
        private set

    /**
     * Смена варианта потока пересоздаёт плеер: переносим текущую позицию,
     * чтобы новый плеер стартовал с неё, а не с начала эпизода.
     */
    fun updateStartPositionMs(value: Long) {
        startPositionMs = value
    }

    /** Название текущего эпизода (Chapter.title) — подпись в контроллах плеера. */
    var episodeTitle: String = ""
        private set

    /** Эпизоды книги в порядке position (DAO: ORDER BY position ASC). */
    private var chaptersUrls: List<String> = emptyList()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun resolve() {
        viewModelScope.launch {
            _state.value = State.Loading
            chaptersUrls = bookChaptersRepository.chapters(bookUrl).map { it.url }
            val chapter = bookChaptersRepository.get(chapterUrl)
            startPositionMs = chapter?.videoPositionMs ?: 0
            episodeTitle = chapter?.title.orEmpty()
            // Шаг 5 (Task 14): эпизод скачан целиком → uri из заявки, играется
            // из кэша (без сети, заголовки не нужны); иначе — резолв как обычно.
            videoDownloadManager.completedVideo(chapterUrl)?.let { local ->
                _state.value = State.Ready(listOf(local))
                return@launch
            }
            when (val r = videoRepository.resolve(chapterUrl)) {
                is Response.Success ->
                    _state.value = if (r.data.isEmpty()) State.Error.Localized(R.string.video_sources_not_found)
                                   else State.Ready(r.data)
                is Response.Error -> _state.value = when (r.exception) {
                    is VideoSourceNotFoundException -> State.Error.Localized(R.string.video_source_not_found)
                    is VideoListNotDeclaredException -> State.Error.Localized(R.string.video_source_no_video)
                    else -> State.Error.Message(r.message)
                }
            }
        }
    }

    fun nextChapterUrl(): String? {
        val i = chaptersUrls.indexOf(chapterUrl)
        return if (i < 0) null else chaptersUrls.getOrNull(i + 1)
    }

    fun prevChapterUrl(): String? {
        val i = chaptersUrls.indexOf(chapterUrl)
        return if (i <= 0) null else chaptersUrls[i - 1]
    }

    /** Переход на эпизод: смена chapterUrl и полный пере-resolve (позиция читается заново). */
    fun switchTo(url: String?) {
        if (url == null || url == chapterUrl) return
        chapterUrl = url
        resolve()
    }

    /**
     * Повторный старт Activity (singleTask, onNewIntent): новая пара
     * bookUrl/chapterUrl из extras → пересадка VM и полный пере-resolve.
     * savedStateHandle перезаписываем, чтобы пересоздание Activity читало
     * уже новое значение, а не extras первого старта.
     */
    fun openFromIntent(bookUrl: String, chapterUrl: String) {
        if (this.bookUrl == bookUrl && this.chapterUrl == chapterUrl) return
        this.bookUrl = bookUrl
        this.chapterUrl = chapterUrl
        savedStateHandle["bookUrl"] = bookUrl
        savedStateHandle["chapterUrl"] = chapterUrl
        resolve()
    }

    fun saveProgress(positionMs: Long, durationMs: Long, markRead: Boolean) {
        readerRepository.saveVideoLastReadState(
            bookUrl = bookUrl,
            chapterUrl = chapterUrl,
            positionMs = positionMs,
            durationMs = durationMs,
            markRead = markRead,
        )
    }

    /** Сохранённый per-book выбор варианта потока (quality-подпись) — null, если не делался. */
    fun savedVideoVariantQuality(): String? = appPreferences.videoVariantForBook(bookUrl)

    /** Запоминает выбор варианта потока для текущей книги (переживает сессии и главы). */
    fun saveVideoVariantQuality(quality: String) {
        appPreferences.setVideoVariantForBook(bookUrl, quality)
    }
}
