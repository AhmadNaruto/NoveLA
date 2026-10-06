package my.noveldokusha.network.interceptors

import com.sun.net.httpserver.HttpServer
import my.noveldokusha.core.domain.CloudfareVerificationBypassFailedException
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
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

    // ── Вызов с бюджетом (маркер BudgetedCall) ────────────────────────────────
    //
    // Бюджетный вызов (NetworkClient.callWithTimeout): ретраи и Thread.sleep-бэкофф
    // нарушали бы обещание «одна попытка» — после callTimeout попытки падают
    // мгновенно, но сон интерсептора идёт в полную величину и вылезает за бюджет.

    /** «Сеть», которая всегда бросает IOException: внутренний интерсептор после ретраев. */
    private fun alwaysFailingClient(proceeds: AtomicInteger): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ServerErrorRetryInterceptor(maxRetries = 3, initialBackoffMs = 50))
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    proceeds.incrementAndGet()
                    throw IOException("boom")
                }
            })
            .build()

    private fun budgetedRequest(url: String): Request =
        Request.Builder().url(url).get()
            .tag(BudgetedCall::class.java, BudgetedCall)
            .build()

    @Test
    fun `budgeted request propagates IOException after a single proceed`() {
        val proceeds = AtomicInteger(0)
        val client = alwaysFailingClient(proceeds)

        val error = assertThrows(IOException::class.java) {
            client.newCall(budgetedRequest("http://example.com/")).execute()
        }

        assertEquals("boom", error.message)
        assertEquals("бюджетный вызов: ретраев и backoff-сна не было", 1, proceeds.get())
    }

    @Test
    fun `budgeted request returns first 503 without retry`() {
        val url = startServer(failCount = Int.MAX_VALUE)

        client.newCall(budgetedRequest(url)).execute().use { response ->
            assertEquals(503, response.code)
        }
        assertEquals("503 ушёл вызывающему с первого раза", 1, hits.get())
    }

    @Test
    fun `request without budget marker still retries IOException`() {
        val proceeds = AtomicInteger(0)
        val client = alwaysFailingClient(proceeds)
        val request = Request.Builder().url("http://example.com/").get().build()

        assertThrows(IOException::class.java) {
            client.newCall(request).execute()
        }
        // maxRetries=3 → 4 попытки, как и до появления маркера
        assertEquals(4, proceeds.get())
    }

    // ── Терминальная ошибка CF-обхода ─────────────────────────────────────────
    //
    // CloudfareVerificationBypassFailedException наследует IOException, но ретрай
    // бессмыслен: каждый повтор заново гонит цикл WebView (15с авто + 35с manual)
    // и съедает бюджет таймаута NetworkClient.

    @Test
    fun `cloudfare bypass failure propagates without retry`() {
        val proceeds = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(ServerErrorRetryInterceptor(maxRetries = 3, initialBackoffMs = 1))
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response {
                    proceeds.incrementAndGet()
                    throw CloudfareVerificationBypassFailedException()
                }
            })
            .build()
        val request = Request.Builder().url("http://example.com/").get().build()

        assertThrows(CloudfareVerificationBypassFailedException::class.java) {
            client.newCall(request).execute()
        }
        assertEquals("терминальная ошибка обхода: ретраев не было", 1, proceeds.get())
    }
}
