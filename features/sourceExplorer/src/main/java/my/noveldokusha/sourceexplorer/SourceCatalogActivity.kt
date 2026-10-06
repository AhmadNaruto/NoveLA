package my.noveldokusha.sourceexplorer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.BaseActivity
import my.noveldokusha.coreui.theme.Theme
import my.noveldokusha.core.utils.Extra_String
import my.noveldokusha.navigation.NavigationRoutes
import my.noveldokusha.scraper.Scraper
import my.noveldokusha.strings.R as StringsR
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class SourceCatalogActivity : BaseActivity() {
    class IntentData : Intent, SourceCatalogStateBundle {
        override var sourceBaseUrl by Extra_String()

        constructor(intent: Intent) : super(intent)
        constructor(ctx: Context, sourceBaseUrl: String) : super(
            ctx,
            SourceCatalogActivity::class.java
        ) {
            this.sourceBaseUrl = sourceBaseUrl
        }
    }

    private val viewModel by viewModels<SourceCatalogViewModel>()

    // До готовности источника показываем спиннер, чтобы VM не создавалась раньше времени
    private val contentReady = mutableStateOf(false)

    @Inject
    internal lateinit var navigationRoutes: NavigationRoutes

    @Inject
    internal lateinit var scraper: Scraper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        }
        addOnBackPressedCallback(backPressedCallback)

        setContent {
            Theme(themeProvider = themeProvider) {
                if (!contentReady.value) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    SourceCatalogScreen(
                        viewModel = viewModel,
                        state = viewModel.state,
                        onSearchTextInputChange = viewModel.state.searchTextInput::value::set,
                        onSearchTextInputSubmit = viewModel::onSearchText,
                        onSearchCatalogSubmit   = viewModel::onSearchCatalog,
                        onListLayoutModeChange  = viewModel.state.listLayoutMode::value::set,
                        onToolbarModeChange     = viewModel.state.toolbarMode::value::set,
                        onOpenSourceWebPage     = {
                            navigationRoutes.webView(this, viewModel.sourceBaseUrl).let(::startActivity)
                        },
                        onBookClicked           = { navigationRoutes.chapters(this, it).let(::startActivity) },
                        onBookLongClicked       = viewModel::addToLibraryToggle,
                        onPressBack             = { backPressedCallback.handleOnBackPressed() },
                        onOpenFilterSheet       = { viewModel.state.isFilterSheetOpen.value = true },
                        onApplyFilters          = viewModel::onApplyFilters,
                        getLibraryBadge         = viewModel::getLibraryBadge,
                        libraryBadgeData        = viewModel.libraryBadgeData,
                    )
                }
            }
        }

        lifecycleScope.launch {
            val sourceBaseUrl = IntentData(intent).sourceBaseUrl
            if (scraper.getCompatibleSourceCatalog(sourceBaseUrl) == null) {
                // Lua-источники ещё не загружены: холодный старт / восстановление
                // после убийства процесса. Ждём догрузку, затем повторяем проверку.
                scraper.awaitLoaded()
                if (scraper.getCompatibleSourceCatalog(sourceBaseUrl) == null) {
                    Timber.e("SourceCatalog: source not found for url=$sourceBaseUrl")
                    toasty.show(StringsR.string.source_not_found, shortDuration = false)
                    finish()
                    return@launch
                }
            }
            contentReady.value = true
        }
    }
}