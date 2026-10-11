package my.noveldokusha.tooling.native_scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LuaNativeTest {

    @Test
    fun testLuaExecutionAndDirectLexborHtmlScraping() {
        val luaEngine = LuaNativeEngine()

        val script = """
            function scrapeNovel(html)
                local doc = html_parse(html)
                local title = doc.title
                
                -- Direct CSS Level 4 select in C!
                local items = html_select(html, ".chapter-list a:has(span.tag)")
                local chapters = {}
                for i = 1, #items do
                    table.insert(chapters, {
                        title = items[i].text,
                        url = items[i].href
                    })
                end
                
                return {
                    book_title = title,
                    total_chapters = #chapters,
                    first_chapter = chapters[1]
                }
            end
        """.trimIndent()

        luaEngine.execute(script)

        val sampleHtml = """
            <html>
                <head><title>Super Novel</title></head>
                <body>
                    <div class="chapter-list">
                        <a href="/ch1"><span class="tag">Free</span>Chapter 1</a>
                        <a href="/ch2"><span>VIP</span>Chapter 2</a>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val jsonResult = luaEngine.callFunctionJson("scrapeNovel", sampleHtml)
        assertNotNull(jsonResult)
        assertTrue(jsonResult!!.contains("Super Novel"))
        assertTrue(jsonResult.contains("Chapter 1"))

        luaEngine.close()
    }
}
