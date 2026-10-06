package my.noveldokusha.features.reader.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Проверка живости URL потока до старта воспроизведения: дохлый адрес
 * с истёкшей CDN-подписью иначе вылезает только вечной буферизацией.
 * Статус нужен окну выбора варианта (бейдж «Недоступен»).
 *
 * Никакого кеша внутри — кешированием владеет Compose-состояние в Activity.
 */
object VideoVariantProbe {

    enum class Status { OK, DEAD, UNKNOWN }

    /**
     * Таймаут быстрой проверки живости. Платформа на дохлом хосте
     * ретраит ~30с (лог: NuCachedSource2 retries left 10…0), а статус OkHttp
     * приходит меньше чем за 8с — мёртвый URL помечаем сразу.
     */
    private const val ALIVE_TIMEOUT_MS = 8_000L

    /**
     * Статус URL — одна HTTP-проверка живости: ветка HLS/контейнера больше
     * не нужна (без парсинга профиля потока они идентичны). Работа уходит
     * на Dispatchers.IO.
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
    ): Status = withContext(Dispatchers.IO) {
        // ponytail: границу длительности даёт только OkHttp callTimeout (8с)
        // и таймауты платформенного сетевого стека (~30с ретраи NuCachedSource2).
        when (okHttpCall(url, headers, client, ALIVE_TIMEOUT_MS) { true }) {
            is CallResult.Ok -> Status.OK
            CallResult.Dead -> Status.DEAD
            CallResult.Unreliable -> Status.UNKNOWN
        }
    }

    /** Результат HTTP-вызова: мёртвость отличаем от сбоя без доказательств. */
    private sealed interface CallResult<out T> {
        data class Ok<T>(val value: T) : CallResult<T>
        /** Доказанная мёртвость: 4xx (кроме 408/429) либо DNS не знает хост. */
        data object Dead : CallResult<Nothing>
        /** Сбой без доказательства: 5xx/408/429/непоследовательный 3xx, таймаут, обрыв. */
        data object Unreliable : CallResult<Nothing>
    }

    /**
     * GET ради данных: клиент всегда выведенный из переданного (общий с
     * плеером не трогаем — там свои таймауты), тело читает блок. Ложные
     * «Недоступен» отсекаем здесь: 2xx → Ok, 4xx → Dead, прочий не-2xx →
     * Unreliable, из исключений мёртвостью считается только DNS (UnknownHost).
     */
    private fun <T> okHttpCall(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        timeoutMs: Long,
        onResponse: (Response) -> T,
    ): CallResult<T> = try {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> addHeader(name, value) }
        }.build()
        client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
                when {
                    response.isSuccessful -> CallResult.Ok(onResponse(response))
                    isDeadHttpCode(response.code) -> CallResult.Dead
                    else -> CallResult.Unreliable
                }
            }
    } catch (e: Exception) {
        if (isDeadFailure(e)) CallResult.Dead else CallResult.Unreliable
    }

    /**
     * 4xx-ответ доказывает мёртвость URL (истёкшая подпись, снятый файл).
     * 408 (таймаут на стороне сервера) и 429 (rate limit) — сбои, не смерть.
     */
    internal fun isDeadHttpCode(code: Int): Boolean =
        code in 400..499 && code != 408 && code != 429

    /**
     * Исключение доказывает мёртвость, только если DNS не знает хост (так и
     * умер new.anime-phoenix.workers.dev). OkHttp иногда заворачивает DNS в
     * IOException с причиной — идём по цепочке причин, максимум 4 шага:
     * дальше обёртка обычно оборвана, и такие случаи считаем сбоем без
     * доказательств.
     */
    internal fun isDeadFailure(e: Exception): Boolean {
        var current: Throwable? = e
        var steps = 0
        while (current != null && steps < 4) {
            if (current is UnknownHostException) return true
            current = current.cause
            steps++
        }
        return false
    }
}
