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

    @Test
    fun testElementColonAndDotMethods() {
        val luaEngine = LuaNativeEngine()

        val script = """
            function testMethods(html)
                local container = html_select_first(html, ".container")
                -- Colon select on element
                local link = container:select_first("a")
                
                -- Colon and dot attr calls
                local colonAttr = link:attr("data-custom")
                local dotAttr = link.attr("href")
                
                return {
                    colon_attr = colonAttr,
                    dot_attr = dotAttr,
                    text = link.text
                }
            end
        """.trimIndent()

        luaEngine.execute(script)

        val sampleHtml = """
            <div class="container">
                <a href="/test-url" data-custom="value123">Link Text</a>
            </div>
        """.trimIndent()

        val jsonResult = luaEngine.callFunctionJson("testMethods", sampleHtml)
        assertNotNull(jsonResult)
        assertTrue(jsonResult!!.contains("value123"))
        assertTrue(jsonResult.contains("/test-url"))
        assertTrue(jsonResult.contains("Link Text"))

        luaEngine.close()
    }

    @Test
    fun testNativePureCUtilities() {
        val luaEngine = LuaNativeEngine()

        val script = """
            function testUtils()
                -- JSON
                local obj = json_parse('{"name":"NoveLA","version":2}')
                local str = json_stringify({greeting = "hello", ok = true})
                
                -- Base64
                local b64 = base64_encode("Hello World")
                local decoded = base64_decode(b64)
                
                -- String utils
                local trimmed = string_trim("   padded string   ")
                local cleaned = string_clean("lots    of   spaces   here")
                local split = string_split("a,b,c", ",")
                
                return {
                    parsed_name = obj.name,
                    b64 = b64,
                    decoded = decoded,
                    trimmed = trimmed,
                    cleaned = cleaned,
                    split_count = #split
                }
            end
        """.trimIndent()

        luaEngine.execute(script)

        val jsonResult = luaEngine.callFunctionJson("testUtils")
        assertNotNull(jsonResult)
        assertTrue(jsonResult!!.contains("NoveLA"))
        assertTrue(jsonResult.contains("Hello World"))
        assertTrue(jsonResult.contains("padded string"))
        assertTrue(jsonResult.contains("lots of spaces here"))
        assertTrue(jsonResult.contains("\"split_count\":3"))

        luaEngine.close()
    }

    @Test
    fun testNetworkBridgeIntegration() {
        val stubBridge = object : LuaNativeBridge {
            override fun httpGet(url: String, configJson: String?): String {
                return """{"success":true,"code":200,"body":"<h1>Mocked Response from $url</h1>","headers":{"content-type":"text/html"}}"""
            }
            override fun httpPost(url: String, body: String?, configJson: String?): String? = null
            override fun httpGetBatch(itemsJson: String, configJson: String?): String? = null
            override fun getPreference(key: String): String = "dark_theme"
            override fun setPreference(key: String, value: String?) {}
            override fun getCookies(url: String): String? = null
            override fun setCookies(url: String, cookiesJson: String) {}
            override fun getLocalStorage(url: String, key: String): String? = null
            override fun showError(title: String, message: String, authUrl: String?) {}
            override fun log(level: Int, message: String) {}
            override fun sleep(ms: Long) {}
        }

        val luaEngine = LuaNativeEngine(stubBridge)

        val script = """
            function testNetwork()
                local res = http_get("https://example.com/api")
                local pref = get_preference("theme")
                return {
                    http_success = res.success,
                    http_code = res.code,
                    body = res.body,
                    theme = pref
                }
            end
        """.trimIndent()

        luaEngine.execute(script)

        val jsonResult = luaEngine.callFunctionJson("testNetwork")
        assertNotNull(jsonResult)
        assertTrue(jsonResult!!.contains("\"http_success\":true"))
        assertTrue(jsonResult.contains("\"http_code\":200"))
        assertTrue(jsonResult.contains("Mocked Response"))
        assertTrue(jsonResult.contains("dark_theme"))

        luaEngine.close()
    }

    @Test
    fun testHasFunctionAndTypedCall() {
        val luaEngine = LuaNativeEngine()

        val script = """
            function greet(name, count)
                -- %d format requires integer count in Lua 5.4!
                return string.format("Hello %s, you have %d notifications!\nLine 2", name, count)
            end
            
            function getItems(page)
                return { page = page, items = { "Item 1", "Item 2" } }
            end
        """.trimIndent()

        luaEngine.execute(script)

        assertTrue(luaEngine.hasFunction("greet"))
        assertTrue(luaEngine.hasFunction("getItems"))
        org.junit.Assert.assertFalse(luaEngine.hasFunction("nonExistentFunction"))

        // Raw string return with preserved newlines
        val rawResult = luaEngine.call("greet", "Alice", 5)
        assertEquals("Hello Alice, you have 5 notifications!\nLine 2", rawResult)

        // JSON table return
        val jsonResult = luaEngine.call("getItems", 2)
        assertNotNull(jsonResult)
        assertTrue(jsonResult!!.contains("\"page\":2"))
        assertTrue(jsonResult.contains("Item 1"))

        luaEngine.close()
    }
}
