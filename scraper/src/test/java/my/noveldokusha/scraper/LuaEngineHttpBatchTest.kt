package my.noveldokusha.scraper

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import my.noveldokusha.network.NetworkClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.Locale

/**
 * http_get_batch: per-URL config (headers/charset/timeout/followRedirects/method),
 * честный success, изоляция ошибок внутри батча и общий executor с http_get.
 *
 * Стиль тот же, что и в LuaEngineHttpConfigTest: реальный LuaEngine, замоканы
 * только Context (filesDir, resources) и NetworkClient (настоящий okhttp3.Response).
 */
class LuaEngineHttpBatchTest {

    private val tempDir: File = Files.createTempDirectory("lua-http-batch").toFile()

    private fun okResponse(request: Request, code: Int = 200, body: ByteArray? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Not Found")
            .body(
                (body ?: "body-for-${request.url}".toByteArray())
                    .toResponseBody("text/html; charset=utf-8".toMediaType())
            )
            .build()

    /** [stub] получает собранный запрос: так эмулируются 404 и упавшие хостеры. */
    private fun createEngine(stub: (Request) -> Response = { okResponse(it) }): Pair<LuaEngine, NetworkClient> {
        val networkClient = mock<NetworkClient>()
        runBlocking {
            whenever(networkClient.call(any(), any())).thenAnswer { inv ->
                stub(inv.getArgument<Request.Builder>(0).build())
            }
            whenever(networkClient.callWithTimeout(any(), any(), any())).thenAnswer { inv ->
                stub(inv.getArgument<Request.Builder>(0).build())
            }
        }

        val context = mock<Context>()
        whenever(context.filesDir).thenReturn(tempDir)
        val resources = mock<Resources>()
        val configuration = Configuration()
        configuration.locale = Locale.US
        whenever(resources.configuration).thenReturn(configuration)
        whenever(context.resources).thenReturn(resources)

        val engine = LuaEngine(context, networkClient)
        engine.httpGetCacheTtlMs = 60_000
        return engine to networkClient
    }

    private fun batchGlobals(engine: LuaEngine): LuaValue =
        runBlocking { engine.loadScript("-- batch probe") }

    private fun batchCall(engine: LuaEngine, items: LuaValue, config: LuaValue = LuaValue.NIL): LuaTable =
        batchGlobals(engine).get("http_get_batch").call(items, config).checktable()

    private fun stringItems(vararg urls: String): LuaTable = LuaTable().also { t ->
        urls.forEachIndexed { i, url -> t.set(i + 1, LuaValue.valueOf(url)) }
    }

    /**
     * Настоящий TimeoutCancellationException: его конструктор internal, поэтому таймаут
     * порождается штатным withTimeout — ровно так же, как это делает NetworkClient.call
     * (withTimeout(TOTAL_TIMEOUT_MS)) для элементов батча без явного timeout.
     */
    private fun realTimeout(): Nothing {
        runBlocking { withTimeout(50) { delay(60_000) } }
        throw IllegalStateException("withTimeout(50) did not fire")
    }

    // ── Обратная совместимость ────────────────────────────────────────────────

    @Test
    fun `array of strings with global config still returns one table per url`() {
        val (engine, networkClient) = createEngine()
        val config = LuaTable().also {
            it.set("headers", LuaTable().also { h -> h.set("Referer", LuaValue.valueOf("https://fixed.example/")) })
        }

        val results = batchCall(engine, stringItems("http://example.com/a", "http://example.com/b"), config)

        assertEquals(2, results.length().toInt())
        val first = results.get(1).checktable()
        assertTrue(first.get("success").toboolean())
        assertEquals("body-for-http://example.com/a", first.get("body").tojstring())
        assertEquals(200, first.get("code").toint())
        assertTrue("headers field must always be present", first.get("headers").istable())
        runBlocking { verify(networkClient, times(2)).call(any(), any()) }
    }

    // ── Per-URL headers ───────────────────────────────────────────────────────

    @Test
    fun `per url headers override globals and keep the rest of the batch headers`() {
        val (engine, networkClient) = createEngine()
        val config = LuaTable().also {
            it.set("headers", LuaTable().also { h ->
                h.set("Referer", LuaValue.valueOf("https://global.example/"))
                h.set("X-Token", LuaValue.valueOf("global-token"))
            })
        }
        val items = LuaTable().also { t ->
            t.set(1, LuaTable().also { item ->
                item.set("url", LuaValue.valueOf("http://example.com/hoster"))
                item.set("headers", LuaTable().also { h -> h.set("Referer", LuaValue.valueOf("https://per-url.example/")) })
            })
        }

        batchCall(engine, items, config)

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient, times(1)).call(captor.capture(), any()) }
        val request = captor.firstValue.build()
        assertEquals("https://per-url.example/", request.header("Referer"))
        assertEquals("global-token", request.header("X-Token"))
        assertTrue("default Accept-Language must survive", request.header("Accept-Language") != null)
    }

    // ── Per-URL charset ───────────────────────────────────────────────────────

    @Test
    fun `per url charset decodes the body`() {
        val text = "Привет"
        val cp1251 = text.toByteArray(Charset.forName("windows-1251"))
        val (engine, _) = createEngine { okResponse(it, 200, cp1251) }
        val items = LuaTable().also { t ->
            t.set(1, LuaTable().also { item ->
                item.set("url", LuaValue.valueOf("http://example.com/cyrillic"))
                item.set("charset", LuaValue.valueOf("windows-1251"))
            })
        }

        val results = batchCall(engine, items)

        // UTF-8-декодирование этих байтов дало бы мусор — проверяем именно per-URL charset.
        assertEquals(text, results.get(1).checktable().get("body").tojstring())
    }

    // ── Per-URL timeout ───────────────────────────────────────────────────────

    @Test
    fun `per url timeout goes to callWithTimeout`() {
        val (engine, networkClient) = createEngine()
        val items = LuaTable().also { t ->
            t.set(1, LuaTable().also { item ->
                item.set("url", LuaValue.valueOf("http://example.com/slow"))
                item.set("timeout", LuaValue.valueOf(1500))
            })
        }

        val results = batchCall(engine, items)

        val captor = argumentCaptor<Request.Builder>()
        runBlocking {
            verify(networkClient).callWithTimeout(captor.capture(), eq(1500L), any())
            verify(networkClient, never()).call(any(), any())
        }
        assertEquals("http://example.com/slow", captor.firstValue.build().url.toString())
        assertTrue(results.get(1).checktable().get("success").toboolean())
    }

    // ── Per-URL followRedirects ───────────────────────────────────────────────

    @Test
    fun `per url followRedirects false goes to the second call argument`() {
        val (engine, networkClient) = createEngine()
        val items = LuaTable().also { t ->
            t.set(1, LuaTable().also { item ->
                item.set("url", LuaValue.valueOf("http://example.com/gate"))
                item.set("followRedirects", LuaValue.FALSE)
            })
        }

        batchCall(engine, items)

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient, times(1)).call(captor.capture(), eq(false)) }
        assertEquals("http://example.com/gate", captor.firstValue.build().url.toString())
    }

    // ── Честный success и изоляция ошибок ─────────────────────────────────────

    @Test
    fun `http 404 fails only its own slot while neighbours stay successful`() {
        val (engine, _) = createEngine { request ->
            if (request.url.toString().contains("missing")) okResponse(request, 404, "gone".toByteArray())
            else okResponse(request)
        }
        val items = stringItems(
            "http://example.com/a",
            "http://example.com/missing",
            "http://example.com/b",
        )

        val results = batchCall(engine, items)

        assertEquals(3, results.length().toInt())
        assertTrue(results.get(1).checktable().get("success").toboolean())
        val failed = results.get(2).checktable()
        assertFalse(failed.get("success").toboolean())
        assertEquals(404, failed.get("code").toint())
        assertEquals("gone", failed.get("body").tojstring())
        assertTrue(failed.get("headers").istable())
        assertTrue(results.get(3).checktable().get("success").toboolean())
    }

    @Test
    fun `network exception fails only its own slot with code -1 and empty headers`() {
        val (engine, _) = createEngine { request ->
            if (request.url.toString().contains("dead")) throw IOException("boom")
            else okResponse(request)
        }
        val items = stringItems(
            "http://example.com/a",
            "http://example.com/dead",
            "http://example.com/b",
        )

        val results = batchCall(engine, items)

        assertEquals(3, results.length().toInt())
        assertTrue(results.get(1).checktable().get("success").toboolean())
        val failed = results.get(2).checktable()
        assertFalse(failed.get("success").toboolean())
        assertEquals(-1, failed.get("code").toint())
        assertTrue(failed.get("body").tojstring().contains("boom"))
        assertEquals(0, failed.get("headers").checktable().length().toInt())
        assertTrue(results.get(3).checktable().get("success").toboolean())
    }

    /**
     * TimeoutCancellationException — подкласс CancellationException. Без отдельного catch
     * воркер пробрасывает его в awaitAll → runBlocking валит ВЕСЬ http_get_batch, вместо
     * errorTable для одного слота (KDoc обещает изоляцию одной мёртвой ссылки).
     */
    @Test
    fun `timeout cancellation fails only its own slot while neighbours stay successful`() {
        val (engine, _) = createEngine { request ->
            if (request.url.toString().contains("slow")) realTimeout()
            else okResponse(request)
        }
        val items = stringItems(
            "http://example.com/a",
            "http://example.com/slow",
            "http://example.com/b",
        )

        val results = batchCall(engine, items)

        assertEquals(3, results.length().toInt())
        assertTrue(results.get(1).checktable().get("success").toboolean())
        val failed = results.get(2).checktable()
        assertFalse(failed.get("success").toboolean())
        assertEquals(-1, failed.get("code").toint())
        assertEquals(0, failed.get("headers").checktable().length().toInt())
        assertTrue(results.get(3).checktable().get("success").toboolean())
    }

    // ── Binary ────────────────────────────────────────────────────────────────

    @Test
    fun `binary batch returns byte tables and keeps per slot success`() {
        val (engine, _) = createEngine { request ->
            if (request.url.toString().contains("missing")) okResponse(request, 404, "gone".toByteArray())
            else okResponse(request, 200, byteArrayOf(0x00, 0x3F, 0xFF.toByte()))
        }
        val items = LuaTable().also { t ->
            listOf("http://example.com/ok", "http://example.com/missing").forEachIndexed { i, url ->
                t.set(i + 1, LuaTable().also { item ->
                    item.set("url", LuaValue.valueOf(url))
                    item.set("binary", LuaValue.TRUE)
                })
            }
        }

        val results = batchCall(engine, items)

        assertEquals(2, results.length().toInt())
        val ok = results.get(1).checktable()
        assertTrue(ok.get("success").toboolean())
        val body = ok.get("body").checktable()
        assertEquals(3, body.length().toInt())
        assertEquals(0, body.get(1).toint())
        assertEquals(0x3F, body.get(2).toint())
        assertEquals(255, body.get(3).toint())  // 0xFF как unsigned-байт Lua
        assertTrue(ok.get("headers").istable())
        val failed = results.get(2).checktable()
        assertFalse(failed.get("success").toboolean())
        assertEquals(404, failed.get("code").toint())
        assertTrue("binary body stays a byte table", failed.get("body").istable())
    }

    // ── Кэш и forceNetwork ────────────────────────────────────────────────────

    @Test
    fun `text batch response is cached and force network bypasses the read`() {
        val (engine, networkClient) = createEngine()
        val url = "http://example.com/catalog"

        batchCall(engine, stringItems(url))                       // network call 1 → запись в кэш
        val cached = batchCall(engine, stringItems(url))          // кэш-хит, сети нет
        engine.withForceNetwork { batchCall(engine, stringItems(url)) }  // байпас чтения → call 2

        assertEquals("body-for-$url", cached.get(1).checktable().get("body").tojstring())
        runBlocking { verify(networkClient, times(2)).call(any(), any()) }
    }

    // ── HEAD ──────────────────────────────────────────────────────────────────

    @Test
    fun `head in batch does not poison the http get cache`() {
        val (engine, networkClient) = createEngine()
        val url = "http://example.com/episode"
        val headItems = LuaTable().also { t ->
            t.set(1, LuaTable().also { item ->
                item.set("url", LuaValue.valueOf(url))
                item.set("method", LuaValue.valueOf("HEAD"))
            })
        }

        batchCall(engine, headItems)              // network call 1 (HEAD)
        batchCall(engine, stringItems(url))       // network call 2 (GET, кэш пуст)

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient, times(2)).call(captor.capture(), any()) }
        assertEquals("HEAD", captor.allValues[0].build().method)
        assertEquals("GET", captor.allValues[1].build().method)
    }

    // ── Валидация ─────────────────────────────────────────────────────────────

    @Test
    fun `item without url throws before any network call`() {
        val (engine, networkClient) = createEngine()
        val items = LuaTable().also { t ->
            t.set(1, LuaTable().also { item -> item.set("charset", LuaValue.valueOf("UTF-8")) })
        }

        val error = assertThrows(IllegalArgumentException::class.java) { batchCall(engine, items) }

        assertTrue(error.message.orEmpty().contains("url"))
        runBlocking { verify(networkClient, never()).call(any(), any()) }
    }

    @Test
    fun `non string item throws instead of being skipped silently`() {
        val (engine, networkClient) = createEngine()
        val items = LuaTable().also { t -> t.set(1, LuaValue.valueOf(42)) }

        val error = assertThrows(IllegalArgumentException::class.java) { batchCall(engine, items) }

        assertTrue(error.message.orEmpty().contains("item #1"))
        runBlocking { verify(networkClient, never()).call(any(), any()) }
    }

    @Test
    fun `unsupported per url method throws before any network call`() {
        val (engine, networkClient) = createEngine()
        val items = LuaTable().also { t ->
            t.set(1, LuaTable().also { item ->
                item.set("url", LuaValue.valueOf("http://example.com/x"))
                item.set("method", LuaValue.valueOf("POST"))
            })
        }

        val error = assertThrows(IllegalArgumentException::class.java) { batchCall(engine, items) }

        assertTrue(error.message.orEmpty().contains("POST"))
        runBlocking { verify(networkClient, never()).call(any(), any()) }
    }
}
