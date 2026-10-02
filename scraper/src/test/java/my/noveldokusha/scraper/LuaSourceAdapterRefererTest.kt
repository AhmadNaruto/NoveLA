package my.noveldokusha.scraper

import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Проверяет чтение глобального referer из Lua-скрипта плагина
 * через публичное свойство SourceInterface.referer.
 *
 * referer — готовый заголовок Referer для картинок (коверов):
 * читается из глобальной переменной скрипта и обрезается (trim).
 * "" = не указано/не строка → авто-рефер от URL картинки.
 */
class LuaSourceAdapterRefererTest {

    private fun adapter(script: String): LuaSourceAdapter {
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

    @Test
    fun `referer global is read into referer property`() {
        val a = adapter("referer = 'https://site.example/'")
        assertEquals("https://site.example/", a.referer)
    }

    @Test
    fun `missing referer global maps to empty`() {
        assertEquals("", adapter("-- no referer").referer)
    }

    @Test
    fun `non-string referer global maps to empty`() {
        // LuaError из optjstring перехватывается в readReferer → "".
        // Понятно: число LuaJ отдаёт как строку ("123"), поэтому
        // нестроковым здесь служит boolean.
        assertEquals("", adapter("referer = true").referer)
    }

    @Test
    fun `table referer global maps to empty`() {
        assertEquals("", adapter("referer = {}").referer)
    }

    @Test
    fun `referer global is trimmed`() {
        val a = adapter("referer = '  https://x/  '")
        assertEquals("https://x/", a.referer)
    }
}
