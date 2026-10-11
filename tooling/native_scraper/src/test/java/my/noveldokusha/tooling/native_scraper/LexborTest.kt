package my.noveldokusha.tooling.native_scraper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexborTest {

    @Test
    fun testBasicParsingAndSelection() {
        val html = """
            <html>
                <head>
                    <title>Test Chapter</title>
                </head>
                <body>
                    <div id="content" class="main-body">
                        <h1 class="title">Chapter 1</h1>
                        <p class="chapter-text">Paragraph 1 with <a href="/ch1">link</a>.</p>
                        <p class="chapter-text">Paragraph 2.</p>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = LexborJsoupFacade.parse(html, "https://example.com/books/123")
        assertNotNull(doc)

        // Title, Head, Body
        assertEquals("Test Chapter", doc.title())
        assertNotNull(doc.head())
        assertNotNull(doc.body())

        // Select by ID
        val contentDiv = doc.selectFirst("#content")
        assertNotNull(contentDiv)

        // Select by class
        val title = doc.selectFirst(".title")
        assertNotNull(title)
        assertEquals("Chapter 1", title?.text())

        // Select all paragraphs
        val paragraphs = doc.select("p.chapter-text")
        assertEquals(2, paragraphs.size)
        assertEquals("Paragraph 1 with link.", paragraphs[0].text())
        assertEquals("Paragraph 2.", paragraphs[1].text())

        // Check relative & absolute attribute resolution (abs:href)
        val link = doc.selectFirst("a")
        assertNotNull(link)
        assertEquals("/ch1", link?.attr("href"))
        assertEquals("https://example.com/ch1", link?.attr("abs:href"))

        // Check inner vs outer HTML
        assertTrue(contentDiv?.outerHtml()?.startsWith("<div") == true)
        assertTrue(contentDiv?.html()?.contains("<h1") == true)
        assertTrue(!contentDiv?.html()?.startsWith("<div")!!)

        doc.close()
    }

    @Test
    fun testCssLevel4HasSelector() {
        val html = """
            <div>
                <div class="card" id="card1">
                    <p class="target">Found Me</p>
                </div>
                <div class="card" id="card2">
                    <span>Other</span>
                </div>
            </div>
        """.trimIndent()

        val doc = LexborJsoupFacade.parse(html)

        // CSS Level 4 :has selector
        val matchingCard = doc.selectFirst(".card:has(p.target)")
        assertNotNull(matchingCard)
        assertEquals("card1", matchingCard?.attr("id"))

        doc.close()
    }

    @Test
    fun testCompoundSelectorWithHierarchyAndIdClass() {
        val html = """
            <html><body>
                <div>
                    <p>
                        <span class="book" id="chapter">Target Chapter Content</span>
                        <span class="book">Not this (no id)</span>
                    </p>
                    <span class="book" id="chapter">Not this (not in p)</span>
                </div>
            </body></html>
        """.trimIndent()

        val doc = LexborJsoupFacade.parse(html)
        val selected = doc.select("div p .book#chapter")
        assertEquals(1, selected.size)
        assertEquals("Target Chapter Content", selected[0].text())
        assertEquals("chapter", selected[0].attr("id"))
        assertEquals("book", selected[0].attr("class"))

        doc.close()
    }

    @Test
    fun testChildNodesTraversal() {
        val html = "<div><p>Hello</p>World<span>!</span></div>"
        val doc = LexborJsoupFacade.parse(html)
        val div = doc.selectFirst("div")
        assertNotNull(div)

        val children = div!!.childNodes()
        assertEquals(3, children.size)
        assertEquals("p", children[0].nodeName())
        assertEquals("#text", children[1].nodeName())
        assertTrue(children[1] is LexborTextNode)
        assertEquals("span", children[2].nodeName())

        doc.close()
    }

    @Test
    fun testUnescapeEntities() {
        val unescaped = LexborJsoupFacade.unescapeEntities("&lt;p&gt;Hello &amp; World &#x3D; 123&lt;/p&gt;")
        assertEquals("<p>Hello & World = 123</p>", unescaped)
    }
}
