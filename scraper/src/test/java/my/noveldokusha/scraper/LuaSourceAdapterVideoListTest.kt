package my.noveldokusha.scraper

import kotlinx.coroutines.runBlocking
import my.noveldokusha.core.Response
import org.junit.Assert.*
import org.junit.Test
import org.luaj.vm2.LuaValue
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LuaSourceAdapterVideoListTest {

    private fun adapter(script: String, engine: LuaEngine = mock()): LuaSourceAdapter {
        val globals = createLuaSandboxGlobals()
        val result = globals.load(script).call()
        val luaScript = if (result.istable()) result else globals
        // withSourceContext пишет в luaEngine.currentSourceId (ThreadLocal) — мок должен вернуть реальный.
        whenever(engine.currentSourceId).thenReturn(ThreadLocal())
        return LuaSourceAdapter(context = mock(), luaScript = luaScript, luaEngine = engine, fileName = null)
    }

    private fun table(code: String): LuaValue =
        createLuaSandboxGlobals().load("return $code").call()

    @Test
    fun `non-table result is error`() {
        val a = adapter("")
        val r = a.convertLuaVideoList(table("('не таблица')"))
        assertTrue("expected error, got $r", r is Response.Error)
    }

    @Test
    fun `empty table is success empty`() {
        val r = adapter("").convertLuaVideoList(table("{}"))
        assertTrue(r is Response.Success)
        assertEquals(emptyList<my.noveldokusha.scraper.domain.VideoSource>(), (r as Response.Success).data)
    }

    @Test
    fun `full item maps url quality headers subtitles`() {
        val r = adapter("").convertLuaVideoList(table("""{
            { url = "https://cdn/x.m3u8", quality = "1080p",
              headers = { ["Referer"] = "https://ref" },
              subtitles = { { url = "https://s/ru.srt", label = "Русский", lang = "ru" } } }
        }"""))
        val v = (r as Response.Success).data.single()
        assertEquals("https://cdn/x.m3u8", v.url)
        assertEquals("1080p", v.quality)
        assertEquals("https://ref", v.headers["Referer"])
        assertEquals("ru", v.subtitles.single().lang)
    }

    @Test
    fun `missing headers and subtitles default to empty`() {
        val r = adapter("").convertLuaVideoList(table("""{ { url = "https://cdn/x.mp4" } }"""))
        val v = (r as Response.Success).data.single()
        assertTrue(v.headers.isEmpty()); assertTrue(v.subtitles.isEmpty()); assertEquals("", v.quality)
    }

    @Test
    fun `items without url are skipped`() {
        val r = adapter("").convertLuaVideoList(table("""{ { quality = "720p" }, { url = "https://a/b.mp4" } }"""))
        assertEquals(1, (r as Response.Success).data.size)
    }

    @Test
    fun `mime hint is normalized to media3 value`() {
        fun mimeOf(lua: String): String? =
            (adapter("").convertLuaVideoList(table("""{ { url = "https://a/b", mime = $lua } }"""))
                    as Response.Success).data.single().mime
        // меди3 принимает только точное "application/x-mpegURL" для HLS
        assertEquals("application/x-mpegURL", mimeOf("\"hls\""))
        assertEquals("application/x-mpegURL", mimeOf("\"m3u8\""))
        assertEquals("application/x-mpegURL", mimeOf("\"application/vnd.apple.mpegurl\""))
        assertEquals("video/mp4", mimeOf("\"mp4\""))
        assertEquals("application/dash+xml", mimeOf("\"mpd\""))
        assertEquals("application/dash+xml", mimeOf("\"application/DASH+XML\""))
        assertEquals("application/dash+xml", mimeOf("\"application/dash+xml\""))
        // video/* — понятно меди3, пропускается как есть
        assertEquals("video/webm", mimeOf("\"video/webm\""))
        // точный application/*-set меди3 (exact-match inferContentType…)
        assertEquals("application/x-rtsp", mimeOf("\"application/x-rtsp\""))
        assertEquals("application/vnd.ms-sstr+xml", mimeOf("\"application/vnd.ms-sstr+xml\""))
    }

    @Test
    fun `unknown application mime falls back to null for URL detection`() {
        fun mimeOf(lua: String): String? =
            (adapter("").convertLuaVideoList(table("""{ { url = "https://a/b", mime = $lua } }"""))
                    as Response.Success).data.single().mime
        // нераспознанный непустой mime заставил бы меди3 выбрать прогрессив
        // БЕЗ фоллбэка к расширению URL — обязан стать null
        assertEquals(null, mimeOf("\"application/octet-stream\""))
        assertEquals(null, mimeOf("\"application/dash+xml; charset=utf-8\""))
        assertEquals(null, mimeOf("\"application/json\""))
        assertEquals(null, mimeOf("\"audio/aac\""))
    }

    @Test
    fun `missing or invalid mime stays null`() {
        fun mimeOf(lua: String): String? =
            (adapter("").convertLuaVideoList(table("""{ { url = "https://a/b", $lua } }"""))
                    as Response.Success).data.single().mime
        assertEquals(null, mimeOf(""))
        assertEquals(null, mimeOf("mime = nil"))
        assertEquals(null, mimeOf("mime = \"mkv\""))
        assertEquals(null, mimeOf("mime = true"))
        assertEquals(null, mimeOf("mime = {}"))
    }

    @Test
    fun `dispatch returns null when getVideoList not declared`() = runBlocking {
        assertNull(adapter("").getVideoList("https://x/ep"))
    }

    @Test
    fun `dispatch calls lua function`() = runBlocking {
        val engine = mock<LuaEngine>()
        whenever(engine.currentSourceId).thenReturn(ThreadLocal())
        val a = adapter("""
            function getVideoList(ep) return { { url = "https://v/" .. ep } } end
        """, engine)
        val r = a.getVideoList("e1") as Response.Success
        assertEquals("https://v/e1", r.data.single().url)
    }

    @Test
    fun `dispatch nil return maps to empty success`() = runBlocking {
        val a = adapter("function getVideoList(ep) end")
        val r = a.getVideoList("e1")
        assertTrue("expected success, got $r", r is Response.Success)
        assertEquals(emptyList<my.noveldokusha.scraper.domain.VideoSource>(), (r as Response.Success).data)
    }

    @Test
    fun `dispatch lua exception maps to error`() = runBlocking {
        val a = adapter("""function getVideoList(ep) error("boom") end""")
        val r = a.getVideoList("e1")
        assertTrue("expected error, got $r", r is Response.Error)
    }
}
