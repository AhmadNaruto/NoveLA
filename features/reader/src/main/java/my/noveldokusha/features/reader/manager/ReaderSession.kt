package my.noveldokusha.features.reader.manager

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import my.noveldokusha.data.AppRepository
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.appPreferences.TranslationSettingsResolver
import my.noveldokusha.features.reader.ReaderRepository
import my.noveldokusha.features.reader.decodeLastReadPosition
import my.noveldokusha.features.reader.domain.ChapterState
import my.noveldokusha.features.reader.domain.ReaderState
import my.noveldokusha.features.reader.domain.ReadingChapterPosStats
import my.noveldokusha.features.reader.domain.chapterReadPercentage
import my.noveldokusha.features.reader.features.ReaderChaptersLoader
import my.noveldokusha.features.reader.features.ReaderLiveTranslation
import my.noveldokusha.features.reader.features.ReaderManualHighlight
import my.noveldokusha.network.interceptors.CloudflareBypassSignal
import my.noveldokusha.features.reader.tools.ChaptersIsReadRoutine
import my.noveldokusha.features.reader.ui.ReaderViewHandlersActions
import my.noveldokusha.text_translator.domain.TranslationManager
import my.noveldokusha.feature.local_database.DAOs.ChapterTranslationDao
import my.noveldokusha.feature.local_database.tables.Chapter
import kotlin.properties.Delegates
import timber.log.Timber

internal class ReaderSession(
    val bookUrl: String,
    initialChapterUrl: String,
    private val appRepository: AppRepository,
    private val appPreferences: AppPreferences,
    private val readerRepository: ReaderRepository,
    readerViewHandlersActions: ReaderViewHandlersActions,
    @param:ApplicationContext private val context: Context,
    translationManager: TranslationManager,
    translationSettingsResolver: TranslationSettingsResolver,
    private val chapterTranslationDao: ChapterTranslationDao,
) {
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("ReaderSession") +
            CoroutineExceptionHandler { _, throwable ->
                Timber.e(throwable, "ReaderSession: uncaught exception in session scope")
            }
    )

    private var chapterUrl: String = initialChapterUrl

    private val readRoutine = ChaptersIsReadRoutine(appRepository)
    private val orderedChapters = mutableListOf<Chapter>()

    private var lastChapterIndex: Int = -1
    private var preloadTriggeredForChapter = -1

    var bookTitle: String? = null
    var bookCoverUrl: String? = null

    var currentChapter: ChapterState by Delegates.observable(
        ChapterState(
            chapterUrl = chapterUrl,
            chapterItemPosition = 0,
            offset = 0,
            savedWithSplit = appPreferences.READER_SENTENCE_SPLITTING.value
        )
    ) { _, old, new ->
        chapterUrl = new.chapterUrl
        readerLiveTranslation.currentChapterUrl = new.chapterUrl
        if (old.chapterUrl != new.chapterUrl) {
            readerRepository.saveBookLastReadPositionState(bookUrl, new, old)
        }
    }

    val readingStats = mutableStateOf<ReadingChapterPosStats?>(null)
    val readingChapterProgressPercentage = derivedStateOf {
        readingStats.value?.chapterReadPercentage() ?: 0f
    }

    val readerLiveTranslation = ReaderLiveTranslation(
        translationManager = translationManager,
        appPreferences = appPreferences,
        translationSettingsResolver = translationSettingsResolver,
        chapterTranslationDao = chapterTranslationDao,
        bookUrl = bookUrl,
    ).also { it.currentChapterUrl = chapterUrl }

    val readerChaptersLoader = ReaderChaptersLoader(
        readerRepository = readerRepository,
        translatorTranslateOrNull = { readerLiveTranslation.translatorState?.translate?.invoke(it) },
        translatorIsActive = { readerLiveTranslation.translatorState != null },
        translatorSourceLanguageOrNull = { readerLiveTranslation.translatorState?.sourceLocale?.language },
        translatorTargetLanguageOrNull = { readerLiveTranslation.translatorState?.targetLocale?.language },
        translatorProvider = {
            // Определяем провайдер по настройке, а не по флагу isUsingOnlineTranslation
            when (appPreferences.TRANSLATION_PROVIDER.value) {
                "GEMINI" -> "gemini"
                "OPENAI" -> "openai"
                "GOOGLE_FREE" -> "google_free"
                else -> "google"
            }
        },
        translatorIsOnline = { readerLiveTranslation.isUsingOnlineTranslation() },
        translatorBatchTranslateOrNull = { readerLiveTranslation.getBatchTranslator() },
        translatorTranslateTitleOrNull = { title, sourceLang, targetLang ->
            translationManager.translateTitle(title, sourceLang, targetLang)
        },
        bookUrl = bookUrl,
        orderedChapters = orderedChapters,
        readerState = ReaderState.INITIAL_LOAD,
        readerViewHandlersActions = readerViewHandlersActions,
        chapterTranslationDao = chapterTranslationDao,
        regexRulesProvider = { appPreferences.effectiveRegexRules(bookUrl) },
        sentenceSplittingEnabledProvider = { appPreferences.READER_SENTENCE_SPLITTING.value },
    )

    val items = readerChaptersLoader.getItems()

    val readerManualHighlight = ReaderManualHighlight(
        items = items,
        scope = scope,
    )

    fun init() {
        initLoadData()
        scope.launch {
            appRepository.libraryBooks.updateLastReadEpochTimeMilli(
                bookUrl,
                System.currentTimeMillis()
            )
            readerRepository.upsertReadingHistory(bookUrl, chapterUrl)
        }

        scope.launch {
            CloudflareBypassSignal.bypassCompleted.collect { host ->
                val bookHost = Uri.parse(bookUrl).host
                if (host == bookHost && readerChaptersLoader.hasLoadingError) {
                    Timber.d("CF bypass completed for $host, retrying failed chapter")
                    readerChaptersLoader.retryFailed()
                }
            }
        }
    }

    private fun initLoadData() {
        scope.launch {
            val book = async(Dispatchers.IO) { appRepository.libraryBooks.get(bookUrl) }
            val chapter = async(Dispatchers.IO) { appRepository.bookChapters.get(chapterUrl) }
            val chaptersList = async(Dispatchers.IO) {
                orderedChapters.also { it.addAll(appRepository.bookChapters.chapters(bookUrl)) }
            }
            val chapterIndex = async(Dispatchers.Default) {
                val list = chaptersList.await()
                Timber.d("ReaderLoad: initialChapterUrl=$chapterUrl chapters=${list.size}")
                val foundIndex = list.indexOfFirst { it.url == chapterUrl }
                Timber.d("ReaderLoad: initial chapterIndex=$foundIndex")
                if (foundIndex == -1) {
                    Timber.w("ReaderLoad: chapterUrl НЕ НАЙДЕН в списке глав, fallback на первую главу! requested=$chapterUrl")
                }
                foundIndex
            }

            chaptersList.await()

            // ВАЖНО: translator должен быть готов ДО первой загрузки главы.
            // Иначе getBatchTranslator() вернёт null (translatorState ещё не создан)
            // и онлайн-провайдер (Gemini/OpenAI) упадёт в поабзацный перевод или ошибку.
            readerLiveTranslation.init()

            bookCoverUrl = book.await()?.coverImageUrl
            bookTitle = book.await()?.title
            readerLiveTranslation.bookTitle = bookTitle ?: ""
            // Позиция из БД может быть закодирована знаком (гранулярность сплита), декодируем.
            val savedPosition = decodeLastReadPosition(chapter.await()?.lastReadPosition ?: 0)
            currentChapter = ChapterState(
                chapterUrl = chapterUrl,
                chapterItemPosition = savedPosition.position,
                offset = chapter.await()?.lastReadOffset ?: 0,
                savedWithSplit = savedPosition.savedWithSplit,
            )

            // Загружаем главу только после того как translatorState готов
            readerChaptersLoader.tryLoadInitial(chapterIndex = chapterIndex.await())
        }
    }

    fun startManualHighlight(itemIndex: Int) {
        readerManualHighlight.startFromItemIndex(itemIndex)
    }

    fun stopManualHighlight() {
        readerManualHighlight.clear()
    }

    fun close() {
        readerChaptersLoader.coroutineContext.cancel()
        readerRepository.saveBookLastReadPositionState(
            bookUrl,
            currentChapter
        )
        readerLiveTranslation.close()
        Timber.d("ReaderSession.close: calling readRoutine.sync()")
        readRoutine.sync()
        readRoutine.close()
        scope.cancel()
    }

    fun reloadReader(chapterLastState: ChapterState) {
        readerManualHighlight.clear()
        readerChaptersLoader.restartInitial(chapterLastState)
    }

    fun updateInfoViewTo(itemIndex: Int, userHasScrolled: Boolean = false) {
        val stats = readerChaptersLoader.getItemContext(
            itemIndex = itemIndex,
            chapterUrl = chapterUrl
        ) ?: return
        readingStats.value = stats

        val progress = stats.chapterReadPercentage()
        val chapterIndex = stats.chapterIndex

        if (chapterIndex != lastChapterIndex) {
            if (lastChapterIndex != -1 && readerChaptersLoader.hasLoadingError) {
                readerChaptersLoader.hasLoadingError = false
                Timber.d("Reset hasLoadingError on chapter change: $lastChapterIndex -> $chapterIndex")
            }
            lastChapterIndex = chapterIndex
            preloadTriggeredForChapter = -1
            scope.launch {
                readerChaptersLoader.pruneItems(chapterIndex)
            }
        }

        if (
            userHasScrolled &&
            preloadTriggeredForChapter != chapterIndex &&
            progress >= 30f &&
            !readerChaptersLoader.isLastChapter(chapterIndex) &&
            !readerChaptersLoader.hasLoadingError
        ) {
            val nextChapterIndex = chapterIndex + 1
            if (!readerChaptersLoader.isChapterIndexLoaded(nextChapterIndex)) {
                preloadTriggeredForChapter = chapterIndex
                scope.launch {
                    readerChaptersLoader.tryLoadNext()
                }
            }
        }
    }

    fun markChapterStartAsSeen(chapterUrl: String) {
        Timber.d("markChapterStartAsSeen: $chapterUrl")
        readRoutine.setReadStart(chapterUrl = chapterUrl)
    }

    fun markChapterEndAsSeen(chapterUrl: String) {
        Timber.d("markChapterEndAsSeen: $chapterUrl")
        readRoutine.setReadEnd(chapterUrl = chapterUrl)
    }

    fun saveCurrentPosition(currentChapter: ChapterState) {
        readerRepository.saveBookLastReadPositionState(
            bookUrl = bookUrl,
            newChapter = currentChapter
        )
    }
}
