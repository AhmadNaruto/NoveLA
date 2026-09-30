package my.noveldokusha.scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Образец видео-плагина из docs/lua-plugin-video-api.md должен проходить
 * тот же контур, что и validateScript (компиляция + загрузка в песочницу),
 * и содержать обязательные функции контракта content_type = "video".
 * Образец извлекается из markdown по маркерам sample:start/sample:end.
 */
class LuaVideoDocSampleTest {

    private fun docSample(): String {
        val candidates = listOf(
            File("docs/lua-plugin-video-api.md"),
            File("../docs/lua-plugin-video-api.md"),
        )
        val doc = candidates.firstOrNull { it.exists() }
            ?: error("docs/lua-plugin-video-api.md not found, tried: " + candidates.map { it.absolutePath })
        val text = doc.readText()
        val start = text.indexOf("<!-- sample:start -->")
        val end = text.indexOf("<!-- sample:end -->")
        assertTrue("sample:start marker missing", start >= 0)
        assertTrue("sample:end marker missing", end > start)
        val block = text.substring(start, end)
        return Regex("```lua\n(.*?)```", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(block)?.groupValues?.get(1)
            ?: error("no ```lua block between sample markers")
    }

    @Test
    fun `sample loads in sandbox without errors`() {
        val globals = createLuaSandboxGlobals()
        // Как в validateScript: compile + исполнение top-level чанка.
        globals.load(docSample()).call()
    }

    @Test
    fun `sample declares video contract`() {
        val globals = createLuaSandboxGlobals()
        val result = globals.load(docSample()).call()
        val luaScript = if (result.istable()) result else globals

        assertEquals("video", luaScript.get("content_type").tojstring())

        val engine = mock<LuaEngine>()
        whenever(engine.currentSourceId).thenReturn(ThreadLocal())
        val adapter = LuaSourceAdapter(
            context = mock(),
            luaScript = luaScript,
            luaEngine = engine,
            fileName = "example_video.lua",
        )
        val required = adapter.requiredLuaFunctions("video")
        assertTrue("getVideoList must be required for video", "getVideoList" in required)
        assertFalse("getChapterText must not be required for video", "getChapterText" in required)
        required.forEach { fn ->
            assertFalse("sample misses required function: $fn", luaScript.get(fn).isnil())
        }
    }
}
