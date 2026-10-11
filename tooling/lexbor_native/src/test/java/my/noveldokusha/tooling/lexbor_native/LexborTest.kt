package my.noveldokusha.tooling.lexbor_native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LexborTest {

    @Test
    fun testBasicParsingAndSelection() {
        val html = """
            <html>
                <body>
                    <div id="content" class="main-body">
                        <h1 class="title">Chapter 1</h1>
                        <p class="chapter-text">Paragraph 1 with <a href="http://example.com">link</a>.</p>
                        <p class="chapter-text">Paragraph 2.</p>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = LexborJsoupFacade.parse(html)
        assertNotNull(doc)

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

        // Check attribute extraction
        val link = doc.selectFirst("a")
        assertNotNull(link)
        assertEquals("http://example.com", link?.attr("href"))

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
}
