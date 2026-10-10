package my.noveldokusha.network.interceptors

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

/**
 * NETWORK interceptor (addNetworkInterceptor): runs after BridgeInterceptor, so it sees the
 * final header set and can fill in what Chrome always sends and put everything in Chrome's order.
 *
 * - Only touches requests whose User-Agent is Chromium-based ("Chrome/NNN"). For Firefox/Safari
 *   presets the request is passed through untouched (the TLS/h2 stack is Chrome-shaped anyway,
 *   so those presets can't be made consistent).
 * - Headers set explicitly by the caller (Lua plugins etc.) are never overwritten.
 * - Accept-Encoding is widened to "gzip, deflate, br"; BridgeInterceptor still un-gzips,
 *   DecodeResponseInterceptor handles br/deflate.
 */
internal class ChromeHeadersInterceptor(
    private val acceptLanguage: () -> String = { defaultAcceptLanguage() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val ua = req.header("User-Agent") ?: return chain.proceed(req)
        val major = CHROME_MAJOR.find(ua)?.groupValues?.get(1)?.toIntOrNull()
            ?: return chain.proceed(req)

        val src = req.headers
        val host = req.url.host
        val accept = src["Accept"]
        val origin = src["Origin"]
        val referer = src["Referer"]

        val kind = when {
            accept?.startsWith("image/") == true -> Kind.IMAGE
            origin != null || src["X-Requested-With"] != null ||
                accept?.contains("json", ignoreCase = true) == true -> Kind.FETCH
            else -> Kind.NAVIGATE
        }

        val isMobile = ua.contains("Mobile")
        val platform = when {
            ua.contains("Android") -> "Android"
            ua.contains("Windows") -> "Windows"
            ua.contains("Macintosh") -> "macOS"
            ua.contains("Linux") -> "Linux"
            else -> "Windows"
        }
        val edge = ua.contains("Edg/") || ua.contains("EdgA/")

        fun site(otherUrl: String?): String {
            if (otherUrl == null) return if (kind == Kind.NAVIGATE) "none" else "same-origin"
            val h = otherUrl.substringAfter("://").substringBefore('/').substringBefore(':')
            return when {
                h.equals(host, true) -> "same-origin"
                registrable(h) == registrable(host) -> "same-site"
                else -> "cross-site"
            }
        }

        val want = LinkedHashMap<String, String?>()
        want["sec-ch-ua"] = secChUa(major, edge)
        want["sec-ch-ua-mobile"] = if (isMobile) "?1" else "?0"
        want["sec-ch-ua-platform"] = "\"$platform\""
        if (kind == Kind.NAVIGATE) want["upgrade-insecure-requests"] = "1"
        want["user-agent"] = ua
        want["accept"] = when (kind) {
            Kind.NAVIGATE -> NAV_ACCEPT
            Kind.IMAGE -> IMG_ACCEPT
            Kind.FETCH -> "*/*"
        }
        want["origin"] = origin
        want["sec-fetch-site"] = when (kind) {
            Kind.NAVIGATE -> site(referer)
            Kind.FETCH -> site(origin ?: referer)
            Kind.IMAGE -> site(referer)
        }
        want["sec-fetch-mode"] = when (kind) {
            Kind.NAVIGATE -> "navigate"; Kind.FETCH -> "cors"; Kind.IMAGE -> "no-cors"
        }
        if (kind == Kind.NAVIGATE && referer == null) want["sec-fetch-user"] = "?1"
        want["sec-fetch-dest"] = when (kind) {
            Kind.NAVIGATE -> "document"; Kind.FETCH -> "empty"; Kind.IMAGE -> "image"
        }
        want["referer"] = referer
        want["accept-encoding"] = "gzip, deflate, br"
        want["accept-language"] = acceptLanguage()
        want["cookie"] = src["Cookie"]
        // Confirmed against a real WebView capture: WebView sends "priority: u=0, i" on navigations.
        if (kind == Kind.NAVIGATE) want["priority"] = "u=0, i"

        val out = Headers.Builder()
        val used = HashSet<String>()
        for ((name, preferred) in want) {
            // caller-supplied value wins over our default (except UA/cookie/origin/referer: already caller's)
            val callerValue = src[name]
            val v = when {
                name == "accept-encoding" -> preferred          // BridgeInterceptor's "gzip" is replaced
                callerValue != null -> callerValue
                else -> preferred
            }
            if (v != null) { out.add(name, v); used.add(name) }
        }
        // everything else (Host, Connection, Content-Type, Content-Length, custom headers) keeps relative order
        for (i in 0 until src.size) {
            val n = src.name(i)
            if (n.lowercase(Locale.US) !in used) out.add(n, src.value(i))
        }
        return chain.proceed(req.newBuilder().headers(out.build()).build())
    }

    private enum class Kind { NAVIGATE, FETCH, IMAGE }

    companion object {
        private val CHROME_MAJOR = Regex("""Chrome/(\d+)""")
        // "image/avif" is intentionally omitted (real Chrome sends it): the app's
        // ImageLoader relies on this Accept, the CDN serves AVIF only when it sees
        // the avif token (Vary: Accept), and AVIF fails to decode on device
        // (HeifDecoderImpl: "videoFrame is a nullptr"). Without the token the CDN
        // answers image/webp or image/jpeg, which decode everywhere.
        private const val NAV_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp," +
                "image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        private const val IMG_ACCEPT = "image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

        private val GREASE_CHARS = arrayOf(" ", "(", ":", "-", ".", "/", ")", ";", "=", "?", "_")
        private val GREASE_VERSIONS = arrayOf("8", "99", "24")
        private val ORDERS = arrayOf(
            intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
            intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0),
        )

        /** Same algorithm as Chromium's GenerateBrandVersionList (seed = major version). */
        fun secChUa(major: Int, edge: Boolean): String {
            val grease = "Not${GREASE_CHARS[major % 11]}A${GREASE_CHARS[(major + 1) % 11]}Brand"
            val list = arrayOfNulls<String>(3)
            val order = ORDERS[major % 6]
            list[order[0]] = "\"$grease\";v=\"${GREASE_VERSIONS[major % 3]}\""
            list[order[1]] = "\"Chromium\";v=\"$major\""
            list[order[2]] = "\"${if (edge) "Microsoft Edge" else "Google Chrome"}\";v=\"$major\""
            return list.joinToString(", ")
        }

        fun defaultAcceptLanguage(): String {
            val l = Locale.getDefault()
            val tag = l.toLanguageTag()
            return if (l.country.isNotEmpty())
                "$tag,${l.language};q=0.9,en-US;q=0.8,en;q=0.7"
            else "$tag,en-US;q=0.9,en;q=0.8"
        }

        // crude eTLD+1 (last two labels); good enough for same-site vs cross-site hint
        private fun registrable(h: String) = h.split('.').takeLast(2).joinToString(".")
    }
}
