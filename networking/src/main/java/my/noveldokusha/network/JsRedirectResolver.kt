package my.noveldokusha.network

import timber.log.Timber
import org.jsoup.nodes.Document

/**
 * Парсит HTML-документ на наличие JS-редиректов (window.location, meta refresh)
 * и возвращает целевой URL, если найден.
 *
 * Некоторые сайты-прокладки (например readnovel.site) отдают HTML с "Loading..."
 * и JS-редиректом на реальный сайт. OkHttp не выполняет JS, поэтому нужно
 * извлекать URL редиректа вручную.
 */
private val META_REFRESH_URL = Regex("""url\s*=\s*['"]?([^'">\s]+)""", RegexOption.IGNORE_CASE)
private val WINDOW_LOCATION_HREF = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
private val WINDOW_LOCATION = Regex("""window\.location\s*=\s*['"]([^'"]+)['"]""")
// substring-матчинг покрывает и window.location.replace(...), и self/top/document.location*
private val LOCATION_METHOD = Regex("""location\.(?:replace|assign)\s*\(\s*['"]([^'"]+)['"]""")
private val LOCATION_HREF = Regex("""location\.href\s*=\s*['"]([^'"]+)['"]""")
private val LOCATION = Regex("""location\s*=\s*['"]([^'"]+)['"]""")
private val SCRIPT_LOCATION_PATTERN = Regex("""(?:window\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]""")

object JsRedirectResolver {

    /**
     * Ищет URL редиректа в HTML-документе.
     * Проверяет: meta refresh, window.location, window.location.href, window.location.replace
     *
     * @param doc Jsoup Document страницы
     * @return URL для редиректа или null, если не найден
     */
    fun resolveRedirectUrl(doc: Document): String? {
        val html = doc.outerHtml()

        // 1. Meta refresh (самый простой случай)
        val metaRefresh = doc.select("meta[http-equiv=refresh]").first()
        if (metaRefresh != null) {
            val content = metaRefresh.attr("content")
            val urlMatch = META_REFRESH_URL.find(content)
            if (urlMatch != null) {
                val target = urlMatch.groupValues[1]
                // javascript: — это не навигация, пропускаем и ищем дальше
                if (!target.startsWith("javascript:", ignoreCase = true)) {
                    val url = toAbsoluteUrl(doc, target)
                    Timber.d("Found meta refresh redirect: $url")
                    return normalizeUrl(url)
                }
            }
        }

        // 2. window.location.href = "..." или location = "..." / location.replace(...) / location.assign(...)
        val locationPatterns = listOf(
            WINDOW_LOCATION_HREF,
            WINDOW_LOCATION,
            LOCATION_METHOD,
            LOCATION_HREF,
            LOCATION,
        )

        for (pattern in locationPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                val url = toAbsoluteUrl(doc, match.groupValues[1])
                Timber.d("Found JS redirect: $url")
                return normalizeUrl(url)
            }
        }

        // 3. Поиск в script-тегах через регулярку по всему HTML
        val scriptMatch = SCRIPT_LOCATION_PATTERN.find(html)
        if (scriptMatch != null) {
            val url = toAbsoluteUrl(doc, scriptMatch.groupValues[1])
            Timber.d("Found script redirect: $url")
            return normalizeUrl(url)
        }

        return null
    }

    /**
     * Резолвит относительный и protocol-relative URL против [doc].location(),
     * заменяет экранированные слеши \/ на /.
     */
    private fun toAbsoluteUrl(doc: Document, rawUrl: String): String {
        var url = rawUrl.replace("\\/", "/").trim()
        if (url.startsWith("javascript:", ignoreCase = true)) return url
        // Если URL относительный — превращаем в абсолютный
        if (!url.startsWith("http://") && !url.startsWith("https://") && !url.startsWith("//")) {
            try {
                val baseUri = doc.location()
                if (baseUri.isNotEmpty()) {
                    val base = java.net.URI(baseUri)
                    url = if (url.startsWith("/")) {
                        "${base.scheme}://${base.host}$url"
                    } else {
                        val parent = baseUri.substringBeforeLast("/")
                        "$parent/$url"
                    }
                }
            } catch (_: Exception) {
                // Если не удалось — оставляем как есть
            }
        }
        // Если URL начинается с // — добавляем протокол
        if (url.startsWith("//")) {
            try {
                val baseUri = doc.location()
                if (baseUri.isNotEmpty()) {
                    val scheme = java.net.URI(baseUri).scheme
                    url = "$scheme:$url"
                }
            } catch (_: Exception) {
                url = "https:$url"
            }
        }
        return url
    }

    /**
     * Нормализует URL: декодирует HTML-энтити (&#x3D; → =), заменяет
     * экранированные слеши \/ на /, удаляет лишние пробелы.
     */
    private fun normalizeUrl(url: String): String {
        var result = org.jsoup.parser.Parser.unescapeEntities(url, false)
            .replace("\\/", "/").trim()
        // Редирект-обёртки могут вкладывать абсолютный URL внутрь пути,
        // напр. "https://a.com/x/https://b.com/y" — берём последний абсолютный URL.
        val idx = maxOf(result.lastIndexOf("https://"), result.lastIndexOf("http://"))
        if (idx > 0) result = result.substring(idx)
        return result
    }
}