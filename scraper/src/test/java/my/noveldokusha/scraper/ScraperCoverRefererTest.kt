package my.noveldokusha.scraper

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Проверяет Scraper.coverReferer: приоритет referer плагина над хостом страницы.
 *
 * Источники подставляются через LuaSourceProvider.loadedSourcesFlow (заглушка),
 * сам Scraper собирается с моками зависимостей.
 */
class ScraperCoverRefererTest {

    private fun source(script: String): LuaSourceAdapter {
        val globals = createLuaSandboxGlobals()
        val result = globals.load(script).call()
        // Как в LuaSourceLoader.loadScript: скрипт без return-таблицы — это сами globals.
        val luaScript = if (result.istable()) result else globals
        return LuaSourceAdapter(
            context = mock(),
            luaScript = luaScript,
            luaEngine = mock(),
            fileName = null
        )
    }

    private fun scraper(vararg sources: SourceInterface): Scraper {
        val list = sources.toList()
        val provider = mock<LuaSourceProvider>()
        whenever(provider.sourcesFlow).thenReturn(flowOf(list))
        whenever(provider.loadedSourcesFlow).thenReturn(MutableStateFlow(list))
        return Scraper(
            networkClient = mock(),
            localSources = emptySet(),
            appPreferences = mock(),
            luaSourceProvider = provider
        )
    }

    @Test
    fun `plugin referer wins over page host`() {
        val s = source(
            """
            baseUrl = 'https://novelsite.example/'
            referer = 'https://cdn.example/'
            """
        )
        assertEquals(
            "https://cdn.example/",
            scraper(s).coverReferer("https://novelsite.example/book/1")
        )
    }

    @Test
    fun `empty plugin referer falls back to page host`() {
        val s = source("baseUrl = 'https://novelsite.example/'")
        assertEquals(
            "https://novelsite.example/",
            scraper(s).coverReferer("https://novelsite.example/book/1")
        )
    }

    @Test
    fun `no matching source falls back to page host`() {
        assertEquals(
            "https://other.example/",
            scraper().coverReferer("https://other.example/book/1")
        )
    }

    @Test
    fun `source on another host does not match page url`() {
        val s = source(
            """
            baseUrl = 'https://novelsite.example/'
            referer = 'https://cdn.example/'
            """
        )
        assertEquals(
            "https://elsewhere.example/",
            scraper(s).coverReferer("https://elsewhere.example/book/1")
        )
    }
}
