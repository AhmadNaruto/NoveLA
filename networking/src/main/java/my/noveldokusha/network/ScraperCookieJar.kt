package my.noveldokusha.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import timber.log.Timber

class ScraperCookieJar : CookieJar {

    // Ленивая инициализация: CookieManager.getInstance() тянет за собой WebViewFactory
    // (загрузка webviewchromium ~сотни мс). Если делать это в field-инициализаторе,
    // WebView грузится на main при создании ScraperNetworkClient на старте приложения.
    // Лениво — инициализация уходит на первый куки-запрос (IO-поток, вне критического пути).
    // Best-effort: на устройстве со сломанным/отсутствующим WebView не роняем сетевые
    // запросы (раньше ошибка инициализации глоталась в App.onCreate).
    // Кэшируется только успешный результат: единичный сбой инициализации не должен
    // навсегда отключать куки для всех последующих запросов.
    @Volatile
    private var cachedManager: CookieManager? = null

    private fun manager(): CookieManager? {
        cachedManager?.let { return it }
        return try {
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
            }.also { cachedManager = it }
        } catch (e: Exception) {
            Timber.e(e, "CookieManager init failed")
            null
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cm = manager()
        if (cm == null) {
            // Только хост/путь и факт отсутствия менеджера — значения кук не логируем.
            Timber.d("cookies for %s%s: CookieManager unavailable", url.host, url.encodedPath)
            return emptyList()
        }
        // Полный URL нужен чтобы OkHttp проверил path при парсинге куки
        val cookieString = cm.getCookie(url.toString())
        val cookies = cookieString
            ?.split(";")
            ?.mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                Cookie.parse(url, trimmed)
            }
            ?: emptyList()

        // Диагностика пустых кук: только ИМЕНА и количество, никогда значения.
        val names = cookies.joinToString(", ") { it.name }
        Timber.d("cookies for %s%s: count=%d names=[%s]", url.host, url.encodedPath, cookies.size, names)
        return cookies
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager() ?: return
        cookies.forEach { cookie ->
            // cookie.toString() возвращает только "name=value" без атрибутов.
            // Строим Set-Cookie строку вручную чтобы сохранить expires и domain,
            // иначе cf_clearance протухнет сразу после закрытия приложения.
            val setCookieString = buildString {
                append("${cookie.name}=${cookie.value}")

                if (cookie.domain.isNotEmpty()) {
                    append("; Domain=${cookie.domain}")
                }
                append("; Path=${cookie.path}")

                if (cookie.expiresAt != Long.MIN_VALUE && cookie.expiresAt != Long.MAX_VALUE) {
                    val date = java.util.Date(cookie.expiresAt)
                    val fmt = java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz",
                        java.util.Locale.US
                    ).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
                    append("; Expires=${fmt.format(date)}")
                }

                if (cookie.secure) append("; Secure")
                if (cookie.httpOnly) append("; HttpOnly")
            }

            // Сохраняем на домен самой куки (может быть .example.com),
            // а не просто на host запроса — критично для cf_clearance
            val saveUrl = "${url.scheme}://${cookie.domain.trimStart('.')}"
            cm.setCookie(saveUrl, setCookieString)
        }
        // flush() один раз после батча
        cm.flush()
    }
}