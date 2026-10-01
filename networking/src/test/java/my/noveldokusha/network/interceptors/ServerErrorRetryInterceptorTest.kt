package my.noveldokusha.network.interceptors

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Регрессия: повторный chain.proceed() при открытом теле предыдущего ответа
 * ронял okhttp IllegalStateException ("previous response is still open").
 * Сервер отдаёт failCount раз 503, затем 200.
 */
class ServerErrorRetryInterceptorTest {

    private lateinit var server: HttpServer
    private lateinit var client: OkHttpClient
    private val hits = AtomicInteger(0)

    @Before
    fun setUp() {
        hits.set(0)
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        client = OkHttpClient.Builder()
            .addInterceptor(ServerErrorRetryInterceptor(maxRetries = 3, initialBackoffMs = 1))
            .build()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun startServer(failCount: Int): String {
        server.createContext("/") { exchange ->
            val code = if (hits.incrementAndGet() <= failCount) 503 else 200
            val body = "body-$code".toByteArray()
            exchange.sendResponseHeaders(code, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        return "http://127.0.0.1:${server.address.port}/"
    }

    @Test
    fun `retries after 503 and returns success`() {
        val url = startServer(failCount = 1)
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("body-200", response.body.string())
        }
        assertEquals(2, hits.get())
    }

    @Test
    fun `returns last response when retries exhausted`() {
        val url = startServer(failCount = Int.MAX_VALUE)
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            assertEquals(503, response.code)
        }
        // maxRetries=3 → 4 попытки
        assertEquals(4, hits.get())
    }

    @Test
    fun `does not retry non-retryable code`() {
        server.createContext("/") { exchange ->
            hits.incrementAndGet()
            val body = "nf".toByteArray()
            exchange.sendResponseHeaders(404, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val url = "http://127.0.0.1:${server.address.port}/"
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            assertEquals(404, response.code)
        }
        assertEquals(1, hits.get())
    }
}
