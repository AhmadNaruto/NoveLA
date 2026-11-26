package my.noveldokusha.features.reader

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import timber.log.Timber
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.AbsListView
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.asLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.coreui.BaseActivity
import my.noveldokusha.coreui.composableActions.SetSystemBarTransparent
import my.noveldokusha.coreui.theme.Theme
import my.noveldokusha.coreui.theme.readerTheme
import my.noveldokusha.core.utils.Extra_Boolean
import my.noveldokusha.core.utils.Extra_String
import my.noveldokusha.core.utils.dpToPx
import my.noveldokusha.core.utils.fadeIn
import my.noveldokusha.data.AppRepository
import my.noveldokusha.data.LibraryBooksRepository
import my.noveldokusha.data.ScraperRepository
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.settings.RegexCleanupSettingsViewModel
import my.noveldokusha.features.reader.domain.ChapterState
import my.noveldokusha.features.reader.domain.ReaderItem
import my.noveldokusha.features.reader.domain.ReaderItemAdapter
import my.noveldokusha.features.reader.domain.ReaderState
import my.noveldokusha.features.reader.domain.indexOfReaderItem
import my.noveldokusha.features.reader.manga.MangaReaderActivity
import my.noveldokusha.features.reader.video.VideoPlayerActivity
import my.noveldokusha.features.reader.manga.viewer.webtoon.accumulatedPixels
import my.noveldokusha.features.reader.manager.ReaderManager
import my.noveldokusha.features.reader.tools.BackgroundImageLoader
import my.noveldokusha.features.reader.tools.FontsLoader
import my.noveldokusha.features.reader.ui.ReaderScreen
import my.noveldokusha.features.reader.ui.ReaderScreenState
import my.noveldokusha.features.reader.ui.ReaderViewHandlersActions
import my.noveldokusha.navigation.NavigationRoutes
import my.noveldokusha.reader.R
import my.noveldokusha.reader.databinding.ActivityReaderBinding
import javax.inject.Inject

@AndroidEntryPoint
class ReaderActivity : BaseActivity() {
    class IntentData : Intent, ReaderStateBundle {
        override var bookUrl by Extra_String()
        override var chapterUrl by Extra_String()

        constructor(intent: Intent) : super(intent)
        constructor(
            ctx: Context,
            bookUrl: String,
            chapterUrl: String,
        ) : super(
            ctx,
            ReaderActivity::class.java
        ) {
            this.bookUrl = bookUrl
            this.chapterUrl = chapterUrl
        }
    }

    @Inject
    lateinit var navigationRoutes: NavigationRoutes

    @Inject
    lateinit var appPreferences: AppPreferences

    @Inject
    internal lateinit var readerViewHandlersActions: ReaderViewHandlersActions

    @Inject
    internal lateinit var readerManager: ReaderManager

    @Inject
    internal lateinit var appRepository: AppRepository

    @Inject
    internal lateinit var libraryBooksRepository: LibraryBooksRepository

    @Inject
    internal lateinit var scraperRepository: ScraperRepository

    private var listIsScrolling = false
    // Время последнего события скролла: используется как watchdog для сброса
    // «залипшего» listIsScrolling, если fling был прерван (notifyDataSetChanged
    // штормом/подгрузкой главы) и IDLE-событие так и не пришло.
    private var lastScrollEventTime = 0L
    // Последний scrollState (IDLE/TOUCH_SCROLL/FLING): watchdog сбрасывает залипший
    // listIsScrolling только для прерванного FLING. При TOUCH_SCROLL палец может лежать
    // неподвижно дольше порога (чтение/выделение) — сброс дёргал бы список под пальцем.
    private var lastScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
    // Последний абзац, для которого был выполнен полный rebind (notifyDataSetChanged).
    // currentReaderItem эмитит на каждый PLAYING/LOADING того же абзаца — повторный
    private val fadeInTextLiveData = MutableLiveData(false)
    // Предотвращает загрузку следующей главы до первого реального скролла пользователя.
    // Сбрасывается в false при каждом открытии Activity, устанавливается в true только при
    // SCROLL_STATE_TOUCH_SCROLL — реальном жесте пальцем (AOSP ставит это состояние раньше
    // FLING, уже на первом драге).
    private var userHasScrolled = false
    // Гейт создаёт ReaderViewModel только в initNovelReader (её property-initializer
    // запускает ReaderSession). Для MANGA-пути VM не создаётся вовсе — флаг защищает
    // onDestroy от обращения к viewModel после finish() манга-маршрута.
    private var novelReaderInitialized = false

    // ── Автопрокрутка (порт webtoon-движка на ListView) ──
    // Job активного цикла автопрокрутки; null = движок остановлен.
    private var autoScrollJob: Job? = null
    // Пауза по касанию/жизненному циклу: любой тач по списку останавливает скролл.
    private var autoScrollPaused = false

    // Double-tap detection for showing/hiding reader info
    private var lastTapTime = 0L
    private val doubleTapThresholdMs = 350L

    private val viewModel by viewModels<ReaderViewModel>()

    private val viewBind by lazy { ActivityReaderBinding.inflate(layoutInflater) }
    private val viewAdapter = object {
        val listView by lazy {
            ReaderItemAdapter(
                this@ReaderActivity,
                viewModel.items,
                viewModel.bookUrl,
                currentTextSelectability = { appPreferences.READER_SELECTABLE_TEXT.value },
                currentFontSize = { appPreferences.READER_FONT_SIZE.value },
                currentLineHeight = { appPreferences.READER_LINE_HEIGHT.value },
                currentParagraphSpacing = { appPreferences.READER_PARAGRAPH_SPACING.value },
                currentLetterSpacing = { appPreferences.READER_LETTER_SPACING.value },
                currentTypeface = { fontsLoader.getTypeFaceNORMAL(appPreferences.READER_FONT_FAMILY.value) },
                currentTypefaceBold = { fontsLoader.getTypeFaceBOLD(appPreferences.READER_FONT_FAMILY.value) },
                currentParallelEnabled = { appPreferences.TRANSLATION_PARALLEL_ENABLED.value },
                currentParallelOrder = { appPreferences.TRANSLATION_PARALLEL_ORDER.value },
                onChapterStartVisible = viewModel::markChapterStartAsSeen,
                onChapterEndVisible = viewModel::markChapterEndAsSeen,
                onReloadReader = viewModel::reloadReader,
                onRetryChapter = { chapterIndex ->
                    // Удаляет все items главы с ошибкой (включая Title/Divider),
                    // сбрасывает chaptersStats и loadedChapters для этой главы,
                    // затем перезагружает её заново. Остальные уже загруженные главы не трогает.
                    viewModel.chaptersLoader.retryChapter(chapterIndex)
                },
                onOpenChapterInBrowser = { url ->
                    navigationRoutes.webView(this@ReaderActivity, url = url, bookUrl = viewModel.bookUrl)
                        .let(::startActivity)
                },
                onClick = {
                    if (appPreferences.READER_SINGLE_TAP_TO_OPEN_SETTINGS.value) {
                        viewModel.state.showReaderInfo.value = !viewModel.state.showReaderInfo.value
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < doubleTapThresholdMs) {
                            viewModel.state.showReaderInfo.value = !viewModel.state.showReaderInfo.value
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                        }
                    }
                },
                currentTextColor = { appPreferences.READER_TEXT_COLOR.value },
                currentManualHighlight = { viewModel.state.settings.manualHighlight.highlightedItem.value },
                currentTextJustify = { appPreferences.READER_TEXT_JUSTIFY.value },
                currentTextHyphenation = { appPreferences.READER_TEXT_HYPHENATION.value },
                currentParagraphIndent = { appPreferences.READER_PARAGRAPH_INDENT.value },
                currentParagraphFirstLetterBold = { appPreferences.READER_PARAGRAPH_FIRST_LETTER_BOLD.value },
                currentTextBold = { appPreferences.READER_TEXT_BOLD.value },
                currentTextItalic = { appPreferences.READER_TEXT_ITALIC.value },
                currentTextUnderline = { appPreferences.READER_TEXT_UNDERLINE.value },
                currentTextShadow = { appPreferences.READER_TEXT_SHADOW.value },
                currentTextSmooth = { appPreferences.READER_TEXT_SMOOTH.value },
                currentMarginLeft = { appPreferences.READER_MARGIN_LEFT.value },
                currentMarginRight = { appPreferences.READER_MARGIN_RIGHT.value },
                currentMarginTop = { appPreferences.READER_MARGIN_TOP.value },
                currentMarginBottom = { appPreferences.READER_MARGIN_BOTTOM.value },
            )
        }
    }

    private val fontsLoader by lazy { FontsLoader(this) }
    // Lazy: конструктор безопасен только после attachBaseContext (getApplicationContext
    // возвращает null в property-initializer). Экземпляр оценивается при первом доступе
    // — below в onCreate, после super.onCreate(). Статический appContext заполняется
    // до setContent → ReaderScreen → backgroundLayer → resolveFile.
    private val backgroundLoader by lazy { BackgroundImageLoader(this) }

    private val backPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModel.onCloseManually()
            finish()
        }
    }

    override fun onDestroy() {
        readerViewHandlersActions.invalidate()
        if (isFinishing && novelReaderInitialized) {
            viewModel.onCloseManually()
        }
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Гарантируем инициализацию фонового лоадера (заполняет статический
        // appContext) до setContent → ReaderScreen → backgroundLayer.
        backgroundLoader

        // Гейт: тип книги решается по сохранённому в БД contentType — без сетевого
        // probe и без обращения к viewModel (её создание запускает ReaderSession
        // в property-initializer ReaderViewModel и гонку с MangaReaderActivity).
        // Быстрый PK-read Room в runBlocking допустим; ошибка чтения БД или
        // отсутствие книги не роняют Activity — падаем в NOVEL, новелл-ридер
        // покажет свой error/retry.
        val intentData = IntentData(intent)
        val bookUrl = intentData.bookUrl
        val chapterUrl = intentData.chapterUrl
        Timber.d("ReaderStart: intent bookUrl=$bookUrl chapterUrl=$chapterUrl")
        val readerType = runBlocking { resolveGateType(libraryBooksRepository, scraperRepository, bookUrl) }
        Timber.d("ReaderStart: readerType=$readerType bookUrl=$bookUrl chapterUrl=$chapterUrl")
        when (readerType) {
            ReaderType.NOVEL -> initNovelReader(bookUrl, chapterUrl)

            ReaderType.MANGA -> {
                Timber.d("ReaderStart: redirecting to MangaReaderActivity bookUrl=$bookUrl chapterUrl=$chapterUrl")
                MangaReaderActivity.start(this, bookUrl, chapterUrl)
                finish()
                return
            }

            ReaderType.VIDEO -> {
                Timber.d("ReaderStart: redirecting to VideoPlayerActivity bookUrl=$bookUrl chapterUrl=$chapterUrl")
                VideoPlayerActivity.start(this, bookUrl, chapterUrl)
                finish()
                return
            }
        }
    }

    /**
     * Инициализация новелл-ридера: дословный перенос тела onCreate (после
     * addCallback) — адаптер списка, observers, контентный setContent,
     * scroll-листенер, snapshotFlow-наблюдатели, intro-scroll. Вызывается
     * синхронно из гейта для NOVEL-глав. bookUrl/chapterUrl приходят из
     * IntentData (гейт), а не из viewModel — первый доступ к viewModel
     * (и создание ReaderSession) происходит только здесь.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    private fun initNovelReader(bookUrl: String, chapterUrl: String) {
        novelReaderInitialized = true
        onBackPressedDispatcher.addCallback(this, backPressedCallback)
        viewBind.listView.adapter = viewAdapter.listView
        readerViewHandlersActions.listView = viewBind.listView

        fadeInTextLiveData.distinctUntilChanged().observe(this) {
            if (it) {
                viewBind.listView.fadeIn(durationMillis = 150)
            }
        }

        lifecycleScope.launch {
            viewModel.onTranslatorChanged.collect {
                viewModel.reloadReader()
            }
        }
        lifecycleScope.launch {
            viewModel.onDisplaySettingsChanged.collect {
                viewAdapter.listView.notifyDataSetChanged()
            }
        }
        lifecycleScope.launch {
            readerManager.onCloseRequested.receiveAsFlow().collect {
                if (!isFinishing) finish()
            }
        }
        readerViewHandlersActions.forceUpdateListViewState = {
            withContext(Dispatchers.Main.immediate) {
                viewAdapter.listView.notifyDataSetChanged()
            }
        }

        readerViewHandlersActions.maintainStartPosition = {
            withContext(Dispatchers.Main.immediate) {
                it()
                val titleIndex = (0 until viewAdapter.listView.count)
                    .indexOfFirst { viewAdapter.listView.getItem(it) is ReaderItem.Title }

                if (titleIndex != -1) {
                    viewBind.listView.setSelection(titleIndex)
                }
            }
        }

        readerViewHandlersActions.setInitialPosition = {
            withContext(Dispatchers.Main.immediate) {
                initialScrollToChapterItemPosition(
                    chapterIndex = it.chapterIndex,
                    chapterItemPosition = it.chapterItemPosition,
                    offset = it.chapterItemOffset
                )
                // Индикатор главы/прогресса обновляется только из onScroll; если
                // setSelectionFromTop не меняет позицию, onScroll не приходит и
                // панель показывает 0/0 до первого свайпа. Дёргаем явно: к этому
                // моменту chaptersStats уже записан (addChapter завершён).
                updateInfoView()
            }
        }

        readerViewHandlersActions.maintainLastVisiblePosition = {
            withContext(Dispatchers.Main.immediate) {
                val oldSize = viewAdapter.listView.count
                val position = viewBind.listView.lastVisiblePosition
                val positionView = position - viewBind.listView.firstVisiblePosition
                val top = viewBind.listView.getChildAt(positionView).run { top - paddingTop }
                it()
                val displacement = viewAdapter.listView.count - oldSize
                viewBind.listView.setSelectionFromTop(position + displacement, top)
            }
        }

        viewModel.manualHighlightScrollToItem.asLiveData().observe(this) {
            scrollToReadingPositionForced(
                chapterIndex = it.chapterIndex,
                chapterItemPosition = it.chapterItemPosition,
            )
        }

        // Notify manually text font changed for list view
        snapshotFlow { viewModel.state.settings.style.textFont.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manually text color changed for list view
        snapshotFlow { viewModel.state.settings.style.textColor.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manually text size changed for list view
        snapshotFlow { viewModel.state.settings.style.textSize.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manually line height changed for list view
        snapshotFlow { viewModel.state.settings.style.lineHeight.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manually paragraph spacing changed for list view
        snapshotFlow { viewModel.state.settings.style.paragraphSpacing.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manually letter spacing changed for list view
        snapshotFlow { viewModel.state.settings.style.letterSpacing.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manually selectable text changed for list view
        snapshotFlow { viewModel.state.settings.isTextSelectable.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify manual highlight changed for list view
        snapshotFlow { viewModel.state.settings.manualHighlight.highlightedItem.value }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify typography style toggles changed for list view
        snapshotFlow {
            listOf(
                viewModel.state.settings.style.textJustify.value,
                viewModel.state.settings.style.textHyphenation.value,
                viewModel.state.settings.style.paragraphIndent.value,
                viewModel.state.settings.style.paragraphFirstLetterBold.value,
                viewModel.state.settings.style.textBold.value,
                viewModel.state.settings.style.textItalic.value,
                viewModel.state.settings.style.textUnderline.value,
                viewModel.state.settings.style.textShadow.value,
                viewModel.state.settings.style.textSmooth.value,
            )
        }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Notify margins changed for list view
        snapshotFlow {
            listOf(
                viewModel.state.settings.style.marginLeft.value,
                viewModel.state.settings.style.marginRight.value,
                viewModel.state.settings.style.marginTop.value,
                viewModel.state.settings.style.marginBottom.value,
            )
        }.drop(1)
            .asLiveData()
            .observe(this) { viewAdapter.listView.notifyDataSetChanged() }

        // Set current screen to be kept bright always or not
        snapshotFlow { viewModel.state.settings.keepScreenOn.value }
            .asLiveData()
            .observe(this) { keepScreenOn ->
                val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                if (keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
            }

        setContent {
            val regexCleanupViewModel: RegexCleanupSettingsViewModel = viewModel(
                key = "regexCleanupSettings",
                factory = viewModelFactory {
                    initializer {
                        RegexCleanupSettingsViewModel(
                            appPreferences = appPreferences,
                            appRepository = appRepository,
                            stateHandler = SavedStateHandle(
                                mapOf("bookUrl" to bookUrl)
                            )
                        )
                    }
                }
            )

            var regexRulesSnapshot by remember { mutableStateOf<List<RegexRule>?>(null) }
            LaunchedEffect(viewModel.state.settings.selectedSetting.value) {
                val selected = viewModel.state.settings.selectedSetting.value
                if (selected == ReaderScreenState.Settings.Type.RegexRules) {
                    regexRulesSnapshot = appPreferences.effectiveRegexRules(bookUrl)
                } else {
                    val before = regexRulesSnapshot
                    regexRulesSnapshot = null
                    if (before != null &&
                        before != appPreferences.effectiveRegexRules(bookUrl)
                    ) {
                        viewModel.reloadReader()
                    }
                }
            }

            Theme(themeProvider) {
                readerTheme {
                    SetSystemBarTransparent()

                    // Reader info
                    ReaderScreen(
                    state = viewModel.state,
                    appPreferences = appPreferences,
                    onTextFontChanged = { appPreferences.READER_FONT_FAMILY.value = it },
                    onTextColorChanged = { appPreferences.READER_TEXT_COLOR.value = it },
                    onBackgroundChanged = { appPreferences.READER_BACKGROUND_IMAGE.value = it },
                    onTextSizeChanged = { appPreferences.READER_FONT_SIZE.value = it },
                    onLineHeightChanged = { appPreferences.READER_LINE_HEIGHT.value = it },
                    onParagraphSpacingChanged = { appPreferences.READER_PARAGRAPH_SPACING.value = it },
                    onLetterSpacingChanged = { appPreferences.READER_LETTER_SPACING.value = it },
                    onSelectableTextChange = { appPreferences.READER_SELECTABLE_TEXT.value = it },
                    onKeepScreenOn = { appPreferences.READER_KEEP_SCREEN_ON.value = it },
                    onDarkModeSelected = { appPreferences.THEME_DARK_MODE.value = it.name; recreate() },
                    onAppThemeChanged = { appPreferences.APP_THEME.value = it.name; recreate() },
                    onFullScreen = { appPreferences.READER_FULL_SCREEN.value = it; recreate() },
                    onSingleTapToOpenSettingsChange = { appPreferences.READER_SINGLE_TAP_TO_OPEN_SETTINGS.value = it },
                    onTextJustifyChange = { appPreferences.READER_TEXT_JUSTIFY.value = it },
                    onTextHyphenationChange = { appPreferences.READER_TEXT_HYPHENATION.value = it },
                    onParagraphIndentChange = { appPreferences.READER_PARAGRAPH_INDENT.value = it },
                    onParagraphFirstLetterBoldChange = { appPreferences.READER_PARAGRAPH_FIRST_LETTER_BOLD.value = it },
                    onSentenceSplittingChange = {
                        appPreferences.READER_SENTENCE_SPLITTING.value = it
                        viewModel.reloadReader()
                    },
                    onTextBoldChange = { appPreferences.READER_TEXT_BOLD.value = it },
                    onTextItalicChange = { appPreferences.READER_TEXT_ITALIC.value = it },
                    onTextUnderlineChange = { appPreferences.READER_TEXT_UNDERLINE.value = it },
                    onTextShadowChange = { appPreferences.READER_TEXT_SHADOW.value = it },
                    onTextSmoothChange = { appPreferences.READER_TEXT_SMOOTH.value = it },
                    onMarginLeftChange = { appPreferences.READER_MARGIN_LEFT.value = it },
                    onMarginRightChange = { appPreferences.READER_MARGIN_RIGHT.value = it },
                    onMarginTopChange = { appPreferences.READER_MARGIN_TOP.value = it },
                    onMarginBottomChange = { appPreferences.READER_MARGIN_BOTTOM.value = it },
                    onTextDefaultsReset = {
                        val splitBefore = appPreferences.READER_SENTENCE_SPLITTING.value
                        appPreferences.READER_FONT_SIZE.value = 14f
                        appPreferences.READER_LINE_HEIGHT.value = 1.35f
                        appPreferences.READER_PARAGRAPH_SPACING.value = 8f
                        appPreferences.READER_LETTER_SPACING.value = 0f
                        appPreferences.READER_FONT_FAMILY.value = "serif"
                        appPreferences.READER_TEXT_JUSTIFY.value = false
                        appPreferences.READER_TEXT_HYPHENATION.value = false
                        appPreferences.READER_PARAGRAPH_INDENT.value = false
                        appPreferences.READER_PARAGRAPH_FIRST_LETTER_BOLD.value = false
                        appPreferences.READER_SENTENCE_SPLITTING.value = false
                        appPreferences.READER_TEXT_BOLD.value = false
                        appPreferences.READER_TEXT_ITALIC.value = false
                        appPreferences.READER_TEXT_UNDERLINE.value = false
                        appPreferences.READER_TEXT_SHADOW.value = false
                        appPreferences.READER_TEXT_SMOOTH.value = true
                        appPreferences.READER_MARGIN_LEFT.value = 16f
                        appPreferences.READER_MARGIN_RIGHT.value = 16f
                        appPreferences.READER_MARGIN_TOP.value = 0f
                        appPreferences.READER_MARGIN_BOTTOM.value = 0f
                        // Смена сплита меняет нумерацию элементов списка: без перестройки
                        // список остаётся сплитнутым при pref=false, позиция сохраняется
                        // в несовместимой нумерации и глава при следующем открытии
                        // восстанавливается не на своём месте. Один reload в конце
                        // блока покрывает итоговое состояние всех преференсов.
                        if (appPreferences.READER_SENTENCE_SPLITTING.value != splitBefore) {
                            viewModel.reloadReader()
                        }
                    },
                    onManualHighlightEnabledChange = {
                        appPreferences.MANUAL_HIGHLIGHT_ENABLED.value = it
                        if (!it) viewModel.stopManualHighlight()
                    },
                    manualHighlight = viewModel.state.settings.manualHighlight,
                    onManualHighlightStart = {
                        val firstVisible = getFirstFullyVisibleItemIndex()
                        // Клампим: нижняя подложка даёт count-1 (за концом списка)
                        viewModel.startManualHighlightAt(
                            firstVisible.coerceAtMost(
                                (viewModel.items.size - 1).coerceAtLeast(0)
                            )
                        )
                    },
                    manualHighlightInitialPosition = run {
                        val x = appPreferences.MANUAL_HIGHLIGHT_POS_X.value
                        val y = appPreferences.MANUAL_HIGHLIGHT_POS_Y.value
                        if (x >= 0f && y >= 0f) x to y else null
                    },
                    onManualHighlightPositionChange = { x, y ->
                        appPreferences.MANUAL_HIGHLIGHT_POS_X.value = x
                        appPreferences.MANUAL_HIGHLIGHT_POS_Y.value = y
                    },
                    onPressBack = {
                        viewModel.onCloseManually()
                        finish()
                    },
                    onOpenChapterInWeb = {
                        // Текущая глава из VM (обновляется при листании); если по какой-то
                        // причине пуста — фолбэк на главу, с которой открыт ридер.
                        val url = viewModel.chapterUrl.ifBlank { chapterUrl }
                        if (url.isNotBlank()) {
                            navigationRoutes.webView(this, url = url, bookUrl = viewModel.bookUrl).let(::startActivity)
                        }
                    },
                    regexCleanupViewModel = regexCleanupViewModel,
                    readerContent = {
                        AndroidView(factory = { viewBind.root })
                    },
                    )

                    if (viewModel.state.showInvalidChapterDialog.value) {
                        BasicAlertDialog(onDismissRequest = {
                            viewModel.state.showInvalidChapterDialog.value = false
                        }) {
                            Text(stringResource(id = R.string.invalid_chapter))
                        }
                    }
                }
            }
        }

        viewBind.listView.setOnScrollListener(
            object : AbsListView.OnScrollListener {
                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) {
                    lastScrollEventTime = SystemClock.elapsedRealtime()
                    updateCurrentReadingPosSavingState(
                        firstVisibleItemIndex = viewAdapter.listView.fromPositionToIndex(
                            viewBind.listView.firstVisiblePosition
                        )
                    )
                    updateInfoView()
                    // Only trigger chapter loading when the user is actually scrolling,
                    // not during programmatic layout changes (e.g. after notifyDataSetChanged).
                    if (listIsScrolling) {
                        updateReadingState()
                    }
                }

                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    lastScrollEventTime = SystemClock.elapsedRealtime()
                    lastScrollState = scrollState
                    listIsScrolling = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL) {
                        userHasScrolled = true
                    }
                    // When the user lifts their finger, check if we need to load more chapters
                    if (!listIsScrolling) {
                        updateReadingState()
                    }
                }
            })

        // Тач по списку — пауза автопрокрутки (пользователь читает/листает сам).
        // actionMasked отделяет ACTION_POINTER_UP: пока второй палец ещё держится,
        // скролл остаётся на паузе. false — событие не перехватываем.
        viewBind.listView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> autoScrollPaused = true
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                -> autoScrollPaused = false
            }
            false
        }

        // Движок автопрокрутки: старт/стоп по префу. Guard autoScrollJob == null
        // защищает от двойного запуска (например, после recreate Activity).
        lifecycleScope.launch {
            appPreferences.READER_AUTOSCROLL_ENABLED.flow().collect { enabled ->
                if (enabled) {
                    autoScrollPaused = false
                    if (autoScrollJob == null) {
                        autoScrollJob = lifecycleScope.launch { runAutoScroll() }
                    }
                } else {
                    autoScrollJob?.cancel()
                    autoScrollJob = null
                }
            }
        }

        snapshotFlow { viewModel.state.settings.fullScreen.value }
            .asLiveData()
            .observe(this) { fullscreen ->
                when {
                    fullscreen -> setupFullScreenMode()
                    else -> setupNormalScreenMode()
                }
            }
        setupSystemBarAppearance()


        viewAdapter.listView.notifyDataSetChanged()
        lifecycleScope.launch {
            delay(200)
            fadeInTextLiveData.postValue(true)
        }



        if (readerViewHandlersActions.introScrollToCurrentChapter) {
            readerViewHandlersActions.introScrollToCurrentChapter = false
            val chapterState = viewModel.readingCurrentChapter
            val chapterStats =
                viewModel.chaptersLoader.chaptersStats[chapterState.chapterUrl] ?: return
            initialScrollToChapterItemPosition(
                chapterIndex = chapterStats.orderedChaptersIndex,
                chapterItemPosition = chapterState.chapterItemPosition,
                offset = chapterState.offset
            )
        }
    }

    private fun setupNormalScreenMode() {
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.displayCutout())
        controller.show(WindowInsetsCompat.Type.systemBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupFullScreenMode() {
        enableEdgeToEdge()
        // Fullscreen mode that ignores any cutout, notch etc.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.displayCutout())
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupSystemBarAppearance() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        combine(
            snapshotFlow { viewModel.state.showReaderInfo.value },
            snapshotFlow { viewModel.state.settings.fullScreen.value }
        ) { showReaderInfo, fullScreen -> showReaderInfo to fullScreen }
            .distinctUntilChangedBy { (showReaderInfo, fullScreen) -> showReaderInfo || !fullScreen }
            .asLiveData().observe(this) { (showReaderInfo, fullScreen) ->
                val show = showReaderInfo || !fullScreen
                when {
                    show -> controller.show(WindowInsetsCompat.Type.statusBars())
                    else -> controller.hide(WindowInsetsCompat.Type.statusBars())
                }
            }
    }

    private fun scrollToReadingPositionForced(chapterIndex: Int, chapterItemPosition: Int) {
        // Search for the item being read otherwise do nothing
        val itemIndex = indexOfReaderItem(
            list = viewModel.items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition
        )
        if (itemIndex == -1) return
        val itemPosition = viewAdapter.listView.fromIndexToPosition(itemIndex)
        val newOffsetPx = 200.dpToPx(this)
        viewAdapter.listView.notifyDataSetChanged()
        viewBind.listView.smoothScrollToPositionFromTop(itemPosition, newOffsetPx, 500)
    }

    private fun scrollToReadingPositionImmediately(chapterIndex: Int, chapterItemPosition: Int) {
        // Search for the item being read otherwise do nothing
        val itemIndex = indexOfReaderItem(
            list = viewModel.items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition
        )
        if (itemIndex == -1) return
        val itemPosition = viewAdapter.listView.fromIndexToPosition(itemIndex)
        val newOffsetPx = 200.dpToPx(this)
        viewAdapter.listView.notifyDataSetChanged()
        viewBind.listView.setSelectionFromTop(itemPosition, newOffsetPx)
        viewAdapter.listView.notifyDataSetChanged()
    }

    /**
     * Индекс первого ПОЛНОСТЬЮ видимого элемента списка, а не первого частично
     * срезанного сверху: если верхний ребёнок обрезан (top < paddingTop), берём
     * следующий. Итерация по детям устойчива к флингу/оверскроллу, когда
     * одновременно обрезано несколько верхних вью.
     */
    private fun getFirstFullyVisibleItemIndex(): Int {
        val listView = viewBind.listView
        if (listView.childCount == 0) {
            return viewAdapter.listView.getFirstVisibleItemIndexGivenPosition(
                listView.firstVisiblePosition
            )
        }
        for (index in 0 until listView.childCount) {
            val child = listView.getChildAt(index) ?: continue
            if (child.top >= listView.paddingTop) {
                return viewAdapter.listView.getFirstVisibleItemIndexGivenPosition(
                    (listView.firstVisiblePosition + index)
                        .coerceAtMost(viewAdapter.listView.count - 1)
                )
            }
        }
        // Все дети обрезаны сверху — крайний случай (оверскролл/загрузка),
        // ограничиваемся нижней подложкой-границей.
        return viewAdapter.listView.getFirstVisibleItemIndexGivenPosition(
            (listView.firstVisiblePosition + listView.childCount - 1)
                .coerceAtMost(viewAdapter.listView.count - 1)
        )
    }

    private fun updateReadingState() {
        val firstVisibleItem = viewBind.listView.firstVisiblePosition
        val lastVisibleItem = viewBind.listView.lastVisiblePosition
        val totalItemCount = viewAdapter.listView.count
        val visibleItemCount =
            if (totalItemCount == 0) 0 else (lastVisibleItem - firstVisibleItem + 1)

        val isTop = visibleItemCount != 0 && firstVisibleItem <= 1
        val isBottom =
            visibleItemCount != 0 && (firstVisibleItem + visibleItemCount) >= totalItemCount - 1

        when (viewModel.chaptersLoader.readerState) {
            ReaderState.IDLE -> {
                // Загружаем следующую главу только если пользователь уже реально скроллил.
                // Это предотвращает преждевременную загрузку при programmatic setSelectionFromTop
                // в начале сессии, когда список ещё короткий и isBottom сразу true.
                if (isBottom && userHasScrolled) {
                    viewModel.chaptersLoader.tryLoadNext()
                }
                if (isTop) {
                    val firstItem = viewModel.items.getOrNull(0)
                    if (firstItem != null && firstItem !is ReaderItem.BookStart) {
                        viewModel.chaptersLoader.tryLoadPrevious()
                    }
                }
            }
            ReaderState.LOADING -> run {}
            ReaderState.INITIAL_LOAD -> run {}
        }
    }

    private fun initialScrollToChapterItemPosition(
        chapterIndex: Int,
        chapterItemPosition: Int,
        offset: Int
    ) {
        val index = indexOfReaderItem(
            list = viewModel.items,
            chapterIndex = chapterIndex,
            chapterItemPosition = chapterItemPosition
        )
        val position = viewAdapter.listView.fromIndexToPosition(index)
        if (index != -1) {
            viewBind.listView.setSelectionFromTop(position, offset)
        }
        fadeInTextLiveData.postValue(true)
    }

    private fun updateInfoView() {
        val lastVisiblePosition = viewBind.listView.lastVisiblePosition
        val itemIndex = viewAdapter.listView.fromPositionToIndex(lastVisiblePosition)
        viewModel.updateInfoViewTo(itemIndex, userHasScrolled = userHasScrolled)
    }

    /** Цикл автопрокрутки: тик каждые 16 мс, мгновенный скролл на накопленные пиксели. */
    private suspend fun CoroutineScope.runAutoScroll() {
        val density = resources.displayMetrics.density
        var totalPixels = 0f
        while (isActive) {
            if (!autoScrollPaused && viewBind.listView.height > 0) {
                // Читаем скорость каждый тик — смена в настройках применяется на лету.
                val speedPx = appPreferences.READER_AUTOSCROLL_SPEED.value * density
                val (pixels, newTotal) = accumulatedPixels(speedPx, FRAME_DELTA_MS, totalPixels)
                totalPixels = newTotal
                if (pixels != 0) viewBind.listView.scrollListBy(pixels)
                // Граница главы: скроллить дальше некуда — догружаем следующую главу,
                // чтобы автопрокрутка могла продолжиться. tryLoadNext @Synchronized
                // с внутренним queue-guard — безопасен при вызове на каждый тик.
                if (!viewBind.listView.canScrollVertically(1)) {
                    viewModel.chaptersLoader.tryLoadNext()
                }
            }
            delay(FRAME_DELTA_MS)
        }
    }

    override fun onPause() {
        // Автопрокрутка не должна двигать список, пока Activity не на экране.
        autoScrollPaused = true
        updateCurrentReadingPosSavingState(
            firstVisibleItemIndex = viewAdapter.listView.fromPositionToIndex(
                viewBind.listView.firstVisiblePosition
            )
        )
        // Explicitly save to database when app pauses
        viewModel.saveCurrentReadingPosition()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()

        // Возобновляем автопрокрутку после возврата на экран, если она включена.
        if (appPreferences.READER_AUTOSCROLL_ENABLED.value) {
            autoScrollPaused = false
        }

        userHasScrolled = false

        if (viewModel.chaptersLoader.hasLoadingError) {
            viewModel.chaptersLoader.retryFailed()
        }
    }

    private fun updateCurrentReadingPosSavingState(firstVisibleItemIndex: Int) {
        val item = viewModel.items.getOrNull(firstVisibleItemIndex) ?: return
        if (item !is ReaderItem.Position) return

        val offset = viewBind.listView.run { getChildAt(0).top - paddingTop }
        viewModel.readingCurrentChapter = ChapterState(
            chapterUrl = item.chapterUrl,
            chapterItemPosition = item.chapterItemPosition,
            offset = offset,
            savedWithSplit = appPreferences.READER_SENTENCE_SPLITTING.value
        )
    }
}

// Период тика автопрокрутки (мс): 16 мс ≈ 60 fps.
private const val FRAME_DELTA_MS = 16L