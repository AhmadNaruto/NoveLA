package my.noveldokusha.scraper

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import kotlinx.coroutines.runBlocking
import my.noveldokusha.network.NetworkClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.nio.file.Files
import java.util.Locale

/**
 * Новые config-ключи Lua HTTP: timeout и method (HEAD) в http_get, headers в http_get_batch.
 *
 * Стиль тот же, что и в LuaEngineHttpGetCacheTest: реальный LuaEngine, замоканы
 * только Context (filesDir, resources) и NetworkClient (настоящий okhttp3.Response).
 */
class LuaEngineHttpConfigTest {

    private val tempDir: File = Files.createTempDirectory("lua-http-config").toFile()

    private fun okResponse(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("body-for-${request.url}".toResponseBody("text/html; charset=utf-8".toMediaType()))
        .build()

    private fun createEngine(): Pair<LuaEngine, NetworkClient> {
        val networkClient = mock<NetworkClient>()
        runBlocking {
            whenever(networkClient.call(any(), any())).thenAnswer { inv ->
                okResponse(inv.getArgument<Request.Builder>(0).build())
            }
            whenever(networkClient.callWithTimeout(any(), any(), any())).thenAnswer { inv ->
                okResponse(inv.getArgument<Request.Builder>(0).build())
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
        // Длинный TTL: в тестах про кэш попадание должно быть гарантировано.
        engine.httpGetCacheTtlMs = 60_000
        return engine to networkClient
    }

    private fun httpGet(engine: LuaEngine, url: String, config: LuaValue): LuaValue {
        val globals = runBlocking { engine.loadScript("-- config probe") }
        return globals.get("http_get").call(LuaValue.valueOf(url), config)
    }

    // ── timeout ──────────────────────────────────────────────────────────────

    @Test
    fun `timeout config is passed to network client as explicit budget`() {
        val (engine, networkClient) = createEngine()
        val config = LuaTable().also { it.set("timeout", LuaValue.valueOf(2500)) }
        val url = "http://example.com/slow"

        val table = httpGet(engine, url, config).checktable()

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient).callWithTimeout(captor.capture(), eq(2500L), any()) }
        assertEquals(url, captor.firstValue.build().url.toString())
        assertTrue(table.get("success").toboolean())
        assertEquals("body-for-$url", table.get("body").tojstring())
    }

    /** Без ключа timeout — прежний путь клиента (call с ретраями), ничего лишнего. */
    @Test
    fun `config without timeout uses regular call`() {
        val (engine, networkClient) = createEngine()
        val url = "http://example.com/plain"

        httpGet(engine, url, LuaValue.NIL)

        runBlocking {
            verify(networkClient).call(any(), any())
            verify(networkClient, never()).callWithTimeout(any(), any(), any())
        }
    }

    // ── method = HEAD ────────────────────────────────────────────────────────

    @Test
    fun `head sends head method and returns empty body`() {
        val (engine, networkClient) = createEngine()
        val config = LuaTable().also { it.set("method", LuaValue.valueOf("HEAD")) }
        val url = "http://example.com/video.mp4"

        val table = httpGet(engine, url, config).checktable()

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient).call(captor.capture(), any()) }
        assertEquals("HEAD", captor.firstValue.build().method)
        assertEquals("no-cache", captor.firstValue.build().header("Cache-Control"))
        assertEquals(200, table.get("code").toint())
        assertTrue(table.get("success").toboolean())
        assertEquals("", table.get("body").tojstring())
    }

    /** Регистр не важен: "head" превращается в HEAD-запрос. */
    @Test
    fun `method is case insensitive`() {
        val (engine, networkClient) = createEngine()
        val lowerCase = LuaTable().also { it.set("method", LuaValue.valueOf("head")) }

        httpGet(engine, "http://example.com/a", lowerCase)

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient).call(captor.capture(), any()) }
        assertEquals("HEAD", captor.firstValue.build().method)
    }

    /** Иное значение — понятная ошибка до похода в сеть. */
    @Test
    fun `unknown method throws illegal argument before any network call`() {
        val (engine, networkClient) = createEngine()
        val unknown = LuaTable().also { it.set("method", LuaValue.valueOf("POST")) }

        val error = assertThrows(IllegalArgumentException::class.java) {
            httpGet(engine, "http://example.com/b", unknown)
        }

        assertTrue(error.message.orEmpty().contains("POST"))
        runBlocking { verify(networkClient, never()).call(any(), any()) }
    }

    /** HEAD не пишет пустое тело в TTL-кэш: следующий GET обязан уйти в сеть. */
    @Test
    fun `head does not poison http get cache`() {
        val (engine, networkClient) = createEngine()
        val url = "http://example.com/episode"
        val head = LuaTable().also { it.set("method", LuaValue.valueOf("HEAD")) }

        httpGet(engine, url, head)                      // network call 1 (HEAD)
        val table = httpGet(engine, url, LuaValue.NIL)  // network call 2 (GET, кэш пуст)

        runBlocking { verify(networkClient, times(2)).call(any(), any()) }
        assertEquals("body-for-$url", table.get("body").tojstring())
    }

    // ── http_get_batch headers ───────────────────────────────────────────────

    @Test
    fun `batch headers are applied to every request and keep defaults`() {
        val (engine, networkClient) = createEngine()
        val referer = "https://fixed.example/"
        val urls = LuaTable().also {
            it.set(1, LuaValue.valueOf("http://example.com/a"))
            it.set(2, LuaValue.valueOf("http://example.com/b"))
        }
        val config = LuaTable().also {
            it.set("headers", LuaTable().also { h -> h.set("Referer", LuaValue.valueOf(referer)) })
        }

        val globals = runBlocking { engine.loadScript("-- batch headers probe") }
        val results = globals.get("http_get_batch").call(urls, config).checktable()
        assertEquals(2, results.length().toInt())

        val captor = argumentCaptor<Request.Builder>()
        runBlocking { verify(networkClient, times(2)).call(captor.capture(), any()) }
        captor.allValues.forEach { builder ->
            val request = builder.build()
            assertEquals(referer, request.header("Referer"))
            assertNotNull("default Accept-Language must survive", request.header("Accept-Language"))
        }
    }
}
