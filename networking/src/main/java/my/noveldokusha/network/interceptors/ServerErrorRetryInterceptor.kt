package my.noveldokusha.network.interceptors

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * Маркер запроса с жёстким бюджетом: его ставит NetworkClient.callWithTimeout
 * через Request.Builder.tag(). Живёт тег только внутри OkHttp — не сериализуется
 * и по проводу не уходит, это чисто локальный сигнал интерсепторам о том, что
 * ретраи и backoff-сон здесь запрещены (бюджет не выдержит).
 */
object BudgetedCall

/**
 * Retry для transient server errors (502/503/504) с экспоненциальным backoff.
 * Ретраит только GET-запросы (идемпотентные).
 */
class ServerErrorRetryInterceptor(
    private val maxRetries: Int = 4,
    private val initialBackoffMs: Long = 500
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.method.equals("GET", ignoreCase = true)) {
            return chain.proceed(request)
        }
        // Cache-only запрос (Coil офлайн: only-if-cached) — OkHttp отвечает
        // 504 из кэша мгновенно и в сеть не ходит. Повтор бессмыслен:
        // ответ офлайн детерминирован, а backoff (до 7.5с) тормозит
        // авто-ретраи ImageView вместо быстрого показа кнопки retry.
        if (request.header("Cache-Control")?.contains("only-if-cached") == true) {
            return chain.proceed(request)
        }
        // Вызов с жёстким бюджетом (NetworkClient.callWithTimeout) — одна попытка
        // без ретраев и без backoff-сна: после callTimeout остальные попытки падают
        // мгновенно (вызов уже отменён), но Thread.sleep идёт в полную величину и
        // вылезает за бюджет (проверено: 8с бюджета → 15.5с факта при 4 ретраях).
        if (request.tag(BudgetedCall::class.java) != null) {
            return chain.proceed(request)
        }

        var lastResponse: Response? = null
        var lastException: IOException? = null

        repeat(maxRetries + 1) { attempt ->
            val response = try {
                chain.proceed(request)
            } catch (e: IOException) {
                lastException = e
                null
            }

            if (response != null && !isRetryable(response.code)) {
                return response
            }

            if (attempt < maxRetries) {
                // Закрываем ДО sleep и ДО следующего chain.proceed: okhttp бросает
                // IllegalStateException ("previous response is still open"), пока тело
                // предыдущего ответа не закрыто. Сон с открытым коннектом держит пул.
                response?.close()
                val backoff = initialBackoffMs * (1 shl attempt) // exponential: 500, 1000, 2000
                Timber.d("ServerErrorRetry: ${response?.code ?: "exception"} on ${request.url.host}, retry ${attempt + 1}/$maxRetries in ${backoff}ms")
                Thread.sleep(backoff)
            } else {
                lastResponse = response // финальная попытка — отдаём вызывающему
            }
        }

        // All retries exhausted
        lastResponse?.let { return it }
        throw lastException ?: IOException("All retries exhausted")
    }

    private fun isRetryable(code: Int): Boolean = code in RETRYABLE_CODES

    companion object {
        // ponytail: 502/503/504 — typical transient CDN errors. 429 — rate limit
        // from CDN (not CF challenge — CF-interop handles those separately).
        private val RETRYABLE_CODES = setOf(429, 502, 503, 504)
    }
}
