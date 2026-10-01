package my.noveldokusha.network

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JsRedirectResolverTest {

    /** Редирект, зашитый в HTML-энтити (&#x3D; = =), должен декодироваться. */
    @Test
    fun `html entity in redirect url is decoded`() {
        val html = """
            <html><body><script>
            window.location.href = '/login-email?next&#x3D;%2Fnovel%2Ffoo%2Fchapter-1';
            </script></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/novel/foo/chapter-1")

        val url = JsRedirectResolver.resolveRedirectUrl(doc)

        assertNotNull(url)
        assertEquals(
            "https://example.com/login-email?next=%2Fnovel%2Ffoo%2Fchapter-1",
            url
        )
    }

    /** Обычный абсолютный JS-редирект работает как раньше. */
    @Test
    fun `plain absolute js redirect is resolved`() {
        val html = """
            <html><body><script>
            window.location.href = "https://other.com/real";
            </script></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/loading")

        assertEquals("https://other.com/real", JsRedirectResolver.resolveRedirectUrl(doc))
    }

    /** Без редиректа возвращается null. */
    @Test
    fun `no redirect returns null`() {
        val html = """<html><body><p>just text</p></body></html>"""
        val doc = Jsoup.parse(html, "https://example.com/chapter-1")

        assertEquals(null, JsRedirectResolver.resolveRedirectUrl(doc))
    }

    /** Meta refresh с относительным URL резолвится против location страницы. */
    @Test
    fun `meta refresh with relative url is resolved`() {
        val html = """
            <html><head>
            <meta http-equiv="refresh" content="0;url=/real-page">
            </head><body></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/novel/foo")

        assertEquals("https://example.com/real-page", JsRedirectResolver.resolveRedirectUrl(doc))
    }

    /** location.assign(...) без window-префикса. */
    @Test
    fun `location assign form is resolved`() {
        val html = """
            <html><body><script>
            location.assign('https://other.com/real');
            </script></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/loading")

        assertEquals("https://other.com/real", JsRedirectResolver.resolveRedirectUrl(doc))
    }

    /** self.location = "..." — покрывается substring-матчем. */
    @Test
    fun `self location form is resolved`() {
        val html = """
            <html><body><script>
            self.location = 'https://other.com/real';
            </script></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://example.com/loading")

        assertEquals("https://other.com/real", JsRedirectResolver.resolveRedirectUrl(doc))
    }
}
