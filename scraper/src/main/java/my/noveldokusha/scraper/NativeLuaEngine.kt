package my.noveldokusha.scraper

import android.content.Context
import com.google.gson.Gson
import com.seanproctor.lua.LuaState
import com.seanproctor.lua.LuaValue
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import my.noveldokusha.core.AppCacheConfig
import my.noveldokusha.core.atomicWrite
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.postRequest
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import timber.log.Timber
import java.io.File
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
public class NativeLuaEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networkClient: NetworkClient
) {
    private val gson = Gson()
    private val luaPrefs by lazy { context.getSharedPreferences("lua_preferences", Context.MODE_PRIVATE) }
    public val currentSourceId: ThreadLocal<String?> = ThreadLocal()
    private val forceNetworkFlag: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    public fun <T> withForceNetwork(block: () -> T): T {
        forceNetworkFlag.set(true)
        try { return block() } finally { forceNetworkFlag.set(false) }
    }

    private val pendingShowError = ThreadLocal<Triple<String, String, String?>?>()

    public fun getPendingShowError(): Triple<String, String, String?>? = pendingShowError.get()

    public fun resetShowError() { pendingShowError.set(null) }

    internal fun setPendingShowError(title: String, message: String, authUrl: String? = null) {
        pendingShowError.set(Triple(title, message, authUrl))
    }

    private val httpGetCache = ConcurrentHashMap<String, HttpGetCacheEntry>()
    internal var httpGetCacheTtlMs: Long = AppCacheConfig.LUA_HTTP_GET_CACHE_TTL_MS

    private data class HttpGetCacheEntry(
        val body: String,
        val success: Boolean,
        val code: Int,
        val headers: Map<String, List<String>>,
        val storedAt: Long
    )

    private fun putHttpGetCache(key: String, entry: HttpGetCacheEntry) {
        val now = System.currentTimeMillis()
        httpGetCache.entries.removeAll { now - it.value.storedAt >= httpGetCacheTtlMs }
        httpGetCache[key] = entry
        if (httpGetCache.size > AppCacheConfig.LUA_HTTP_GET_CACHE_MAX_ENTRIES ||
            httpGetCache.values.sumOf { it.body.length } > AppCacheConfig.LUA_HTTP_GET_CACHE_MAX_BODY_BYTES
        ) {
            httpGetCache.clear()
        }
    }

    public fun clearMemoryCache() {
        httpGetCache.clear()
    }

    private val bytecodeDir: File
        get() = File(context.filesDir, "lua_native_bytecode")

    private val usedBytecodeCache = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    public fun pruneBytecodeCache() {
        bytecodeDir.listFiles()?.forEach { file ->
            if (file.extension == "luac" && !usedBytecodeCache.contains(file.name)) {
                Timber.d("Pruning orphan native Lua bytecode: ${file.name}")
                file.delete()
            }
        }
    }

    public fun beginBytecodeCacheCycle() {
        usedBytecodeCache.clear()
    }

    public fun clearBytecodeCache() {
        usedBytecodeCache.clear()
        bytecodeDir.deleteRecursively()
    }

    private fun bytecodeCacheFile(luaCode: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(luaCode.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return File(bytecodeDir, "$hash-v54.luac")
    }

    /**
     * Creates a new sandboxed [LuaState] instance with host APIs registered.
     */
    public fun newSandboxState(): LuaState {
        val lua = LuaState()
        registerHostApis(lua)
        return lua
    }

    /**
     * Loads a script chunk into [lua], utilizing disk bytecode cache when available.
     */
    public fun loadScriptChunk(lua: LuaState, luaCode: String, chunkName: String = "=(source)"): LuaValue.Function {
        val cacheFile = bytecodeCacheFile(luaCode)
        if (cacheFile.exists()) {
            try {
                val bytes = cacheFile.readBytes()
                usedBytecodeCache.add(cacheFile.name)
                return lua.load(bytes, chunkName)
            } catch (e: Exception) {
                Timber.w(e, "Corrupt native Lua bytecode cache, recompiling: ${cacheFile.name}")
                cacheFile.delete()
            }
        }
        val fn = lua.load(luaCode, chunkName)
        try {
            val bytes = lua.dump(fn)
            if (bytes.isNotEmpty()) {
                cacheFile.parentFile?.mkdirs()
                atomicWrite(cacheFile, bytes)
                usedBytecodeCache.add(cacheFile.name)
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to save native Lua bytecode cache: ${cacheFile.name}")
        }
        return fn
    }

    // ── HTTP Implementation ───────────────────────────────────────────────────

    private data class HttpGetParams(
        val url: String,
        val pluginHeaders: Map<String, String>,
        val charset: String,
        val binary: Boolean,
        val followRedirects: Boolean,
        val method: String,
        val timeoutMs: Long?,
        val logTag: String,
    )

    private fun checkHttpMethod(fnName: String, rawMethod: String): String {
        val method = rawMethod.uppercase(Locale.ROOT)
        if (method != "GET" && method != "HEAD") {
            throw IllegalArgumentException("$fnName: unsupported method \"$rawMethod\", allowed: GET, HEAD")
        }
        return method
    }

    private fun isSsrfSafe(url: String): Boolean {
        val host = try { URI(url).host } catch (_: Exception) { null } ?: return false
        if (host.isEmpty()) return false
        return try {
            InetAddress.getAllByName(host).all { addr ->
                !addr.isLoopbackAddress &&
                !addr.isSiteLocalAddress &&
                !addr.isLinkLocalAddress &&
                !addr.isAnyLocalAddress &&
                addr.hostAddress != "10.0.2.2"
            }
        } catch (_: Exception) { false }
    }

    private fun systemAcceptLanguage(): String =
        Locale.getDefault().let { loc ->
            val lang = loc.language
            val country = loc.country
            if (country.isNotEmpty()) "$lang-$country,$lang;q=0.9,en;q=0.8" else "$lang,en;q=0.8"
        }

    private fun refererFromUrl(url: String): String = try {
        val uri = URI(url)
        "${uri.scheme}://${uri.host}/"
    } catch (_: Exception) { "" }

    private fun defaultHeaders(url: String): Map<String, String> = mapOf(
        "Accept-Language" to systemAcceptLanguage(),
        "Referer" to refererFromUrl(url)
    )

    private fun detectContentType(body: String): String = when {
        body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> "application/json; charset=utf-8"
        body.contains("=") -> "application/x-www-form-urlencoded"
        else -> "text/plain; charset=utf-8"
    }

    private fun createResponseTable(
        lua: LuaState,
        success: Boolean,
        body: String,
        code: Int,
        headers: Map<String, List<String>> = emptyMap()
    ): LuaValue.Table {
        val t = lua.newTable()
        t["success"] = success
        t["body"] = body
        t["code"] = code.toLong()
        val h = lua.newTable()
        headers.forEach { (k, values) ->
            val vt = lua.newTable()
            values.forEachIndexed { i, v -> vt[i + 1] = LuaValue.Str(v) }
            h[k.lowercase(Locale.ROOT)] = vt
        }
        t["headers"] = h

        val toHtmlFn = lua.call(lua.load("return function(self) return html_parse(type(self) == 'table' and self.body or self) end")).getOrNull(0) ?: LuaValue.Nil
        t["toHtml"] = toHtmlFn
        t["to_html"] = toHtmlFn
        return t
    }

    private fun createResponseTableBinary(
        lua: LuaState,
        bytes: ByteArray,
        code: Int,
        headers: Map<String, List<String>> = emptyMap()
    ): LuaValue.Table {
        val t = lua.newTable()
        t["success"] = code in 200..299
        val bodyTable = lua.newTable()
        for (i in bytes.indices) {
            bodyTable[i + 1] = LuaValue.Integer((bytes[i].toInt() and 0xFF).toLong())
        }
        t["body"] = bodyTable
        t["code"] = code.toLong()
        val h = lua.newTable()
        headers.forEach { (k, values) ->
            val vt = lua.newTable()
            values.forEachIndexed { i, v -> vt[i + 1] = LuaValue.Str(v) }
            h[k.lowercase(Locale.ROOT)] = vt
        }
        t["headers"] = h
        return t
    }

    private fun createErrorTable(lua: LuaState, message: String, code: Int = 0): LuaValue.Table {
        val t = lua.newTable()
        t["success"] = false
        t["body"] = message
        t["code"] = code.toLong()
        t["headers"] = lua.newTable()
        return t
    }

    private suspend fun executeHttpGet(
        lua: LuaState,
        params: HttpGetParams,
        sourceId: String?,
        forceNetwork: Boolean
    ): LuaValue.Table {
        val isHead = params.method == "HEAD"
        val headers = defaultHeaders(params.url) + params.pluginHeaders
        val cacheReadable = !isHead &&
            (!params.binary || AppCacheConfig.LUA_CACHE_BINARY) &&
            (!forceNetwork || !AppCacheConfig.LUA_FORCE_NETWORK_BYPASS)
        val cacheKey = "${params.url}|${params.charset}|${sourceId ?: "default"}|${headers.toString().hashCode()}"

        if (cacheReadable) {
            httpGetCache[cacheKey]?.let { entry ->
                if (System.currentTimeMillis() - entry.storedAt < httpGetCacheTtlMs) {
                    Timber.d("${params.logTag} native cache hit: ${params.url}")
                    return createResponseTable(lua, entry.success, entry.body, entry.code, entry.headers)
                }
            }
        }

        if (!isSsrfSafe(params.url)) {
            return createErrorTable(lua, "SSRF: Blocked address for ${params.url}")
        }

        return withContext(Dispatchers.IO) {
            val builder = getRequest(params.url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (isHead) {
                builder.head()
                builder.header("Cache-Control", "no-cache")
            }
            if (forceNetwork) builder.header("Cache-Control", "no-cache")
            sourceId?.let { sid -> if (sid.isNotBlank()) builder.tag(String::class.java, "source:$sid") }

            val response = if (params.timeoutMs != null) {
                networkClient.callWithTimeout(builder, params.timeoutMs, followRedirects = params.followRedirects)
            } else {
                networkClient.call(builder, followRedirects = params.followRedirects)
            }

            response.use { r ->
                val bytes = if (isHead) ByteArray(0) else r.body.bytes()
                if (params.binary) {
                    createResponseTableBinary(lua, bytes, r.code, r.headers.toMultimap())
                } else {
                    val body = String(bytes, java.nio.charset.Charset.forName(params.charset))
                    if (!isHead && AppCacheConfig.isCacheableStatus(r.code)) {
                        putHttpGetCache(cacheKey, HttpGetCacheEntry(body, true, r.code, r.headers.toMultimap(), System.currentTimeMillis()))
                    }
                    createResponseTable(lua, r.isSuccessful, body, r.code, r.headers.toMultimap())
                }
            }
        }
    }

    private fun parseHeaders(table: LuaValue?): Map<String, String> {
        val res = mutableMapOf<String, String>()
        if (table is LuaValue.Table) {
            for ((k, v) in table.toMap()) {
                val key = k.optString()
                val value = v.optString()
                if (!key.isNullOrBlank() && value != null) {
                    res[key] = value
                }
            }
        }
        return res
    }

    public fun registerHostApis(lua: LuaState) {
        // http_get(url [, config])
        lua.register("http_get") { args ->
            val url = args.getOrNull(0)?.optString() ?: return@register listOf(createErrorTable(lua, "Missing url"))
            val config = args.getOrNull(1) as? LuaValue.Table
            val pluginHeaders = parseHeaders(config?.get("headers"))
            val charset = config?.getString("charset", "UTF-8") ?: "UTF-8"
            val binary = config?.getBoolean("binary", false) ?: false
            val followRedirects = config?.getBoolean("followRedirects", true) ?: true
            val method = checkHttpMethod("http_get", config?.getString("method", "GET") ?: "GET")
            val timeoutMs = config?.getLong("timeout", 0L)?.takeIf { it > 0 }
            val sourceId = currentSourceId.get()
            val forceNetwork = forceNetworkFlag.get() == true

            val params = HttpGetParams(
                url = url,
                pluginHeaders = pluginHeaders,
                charset = charset,
                binary = binary,
                followRedirects = followRedirects,
                method = method,
                timeoutMs = timeoutMs,
                logTag = "native_http_get"
            )

            try {
                val res = runBlocking { executeHttpGet(lua, params, sourceId, forceNetwork) }
                listOf(res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "native http_get failed: $url")
                listOf(createErrorTable(lua, e.message ?: "Network error"))
            }
        }

        // http_post(url, body [, config])
        lua.register("http_post") { args ->
            val url = args.getOrNull(0)?.optString() ?: return@register listOf(createErrorTable(lua, "Missing url"))
            if (!isSsrfSafe(url)) return@register listOf(createErrorTable(lua, "SSRF: Blocked address for $url"))
            val bodyStr = args.getOrNull(1)?.optString() ?: ""
            val config = args.getOrNull(2) as? LuaValue.Table
            val pluginHeaders = parseHeaders(config?.get("headers"))
            val charset = config?.getString("charset", "UTF-8") ?: "UTF-8"
            val headers = defaultHeaders(url) + pluginHeaders
            val sourceId = currentSourceId.get()
            val forceNetwork = forceNetworkFlag.get() == true

            try {
                val res = runBlocking {
                    withContext(Dispatchers.IO) {
                        val mediaType = (headers["Content-Type"] ?: detectContentType(bodyStr)).toMediaType()
                        val body = bodyStr.toRequestBody(mediaType)
                        val builder = postRequest(url, body = body, headers = headers.toHeaders())
                        if (forceNetwork) builder.header("Cache-Control", "no-cache")
                        sourceId?.let { sid -> if (sid.isNotBlank()) builder.tag(String::class.java, "source:$sid") }
                        networkClient.call(builder).use { r ->
                            val bytes = r.body.bytes()
                            val s = String(bytes, java.nio.charset.Charset.forName(charset))
                            createResponseTable(lua, r.isSuccessful, s, r.code, r.headers.toMultimap())
                        }
                    }
                }
                listOf(res)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "native http_post failed: $url")
                listOf(createErrorTable(lua, e.message ?: "Network error"))
            }
        }

        // http_get_batch(items [, config])
        lua.register("http_get_batch") { args ->
            val itemsTable = args.getOrNull(0) as? LuaValue.Table ?: return@register listOf(lua.newTable())
            val globalConfig = args.getOrNull(1) as? LuaValue.Table
            val globalHeaders = parseHeaders(globalConfig?.get("headers"))
            val globalCharset = globalConfig?.getString("charset", "UTF-8") ?: "UTF-8"
            val globalBinary = globalConfig?.getBoolean("binary", false) ?: false
            val globalFollowRedirects = globalConfig?.getBoolean("followRedirects", true) ?: true
            val globalTimeout = globalConfig?.getLong("timeout", 0L)?.takeIf { it > 0 }
            val sourceId = currentSourceId.get()
            val forceNetwork = forceNetworkFlag.get() == true

            val results = runBlocking {
                coroutineScope {
                    val deferreds = itemsTable.toList().mapIndexed { index, itemVal ->
                        async {
                            val url = when (itemVal) {
                                is LuaValue.Str -> itemVal.value
                                is LuaValue.Table -> itemVal.getString("url")
                                else -> null
                            } ?: return@async index to createErrorTable(lua, "Invalid item at index ${index + 1}")

                            val itemHeaders = if (itemVal is LuaValue.Table) parseHeaders(itemVal["headers"]) else emptyMap()
                            val itemCharset = if (itemVal is LuaValue.Table) itemVal.getString("charset", globalCharset) ?: globalCharset else globalCharset
                            val itemBinary = if (itemVal is LuaValue.Table) itemVal.getBoolean("binary", globalBinary) else globalBinary
                            val itemFollow = if (itemVal is LuaValue.Table) itemVal.getBoolean("followRedirects", globalFollowRedirects) else globalFollowRedirects
                            val itemTimeout = if (itemVal is LuaValue.Table) itemVal.getLong("timeout", globalTimeout ?: 0L).takeIf { it > 0 } else globalTimeout

                            val params = HttpGetParams(
                                url = url,
                                pluginHeaders = globalHeaders + itemHeaders,
                                charset = itemCharset,
                                binary = itemBinary,
                                followRedirects = itemFollow,
                                method = "GET",
                                timeoutMs = itemTimeout,
                                logTag = "native_http_get_batch"
                            )
                            try {
                                index to executeHttpGet(lua, params, sourceId, forceNetwork)
                            } catch (e: Exception) {
                                index to createErrorTable(lua, e.message ?: "Batch request error")
                            }
                        }
                    }
                    deferreds.awaitAll()
                }
            }

            val outTable = lua.newTable()
            results.forEach { (index, table) ->
                outTable[index + 1] = table
            }
            listOf(outTable)
        }

        // Cookies
        lua.register("get_cookies") { args ->
            val rawUrl = args.getOrNull(0)?.optString() ?: return@register listOf(lua.newTable())
            val httpUrl = rawUrl.toHttpUrl()
            val t = lua.newTable()
            networkClient.cookieJar.loadForRequest(httpUrl).forEach { c ->
                t[c.name] = c.value
            }
            listOf(t)
        }

        lua.register("set_cookies") { args ->
            val rawUrl = args.getOrNull(0)?.optString() ?: return@register emptyList()
            val cookiesTable = args.getOrNull(1) as? LuaValue.Table ?: return@register emptyList()
            val httpUrl = rawUrl.toHttpUrl()
            val cookies = cookiesTable.toMap().mapNotNull { (k, v) ->
                val name = k.optString() ?: return@mapNotNull null
                val value = v.optString() ?: ""
                okhttp3.Cookie.Builder()
                    .domain(httpUrl.host)
                    .name(name)
                    .value(value)
                    .build()
            }
            networkClient.cookieJar.saveFromResponse(httpUrl, cookies)
            emptyList()
        }

        // Preferences & LocalStorage
        lua.register("get_preference") { args ->
            val key = args.getOrNull(0)?.optString() ?: return@register listOf(LuaValue.Str(""))
            val v = luaPrefs.getString(key, "") ?: ""
            listOf(LuaValue.Str(v))
        }

        lua.register("set_preference") { args ->
            val key = args.getOrNull(0)?.optString() ?: return@register emptyList()
            val v = args.getOrNull(1)?.optString() ?: ""
            luaPrefs.edit().putString(key, v).apply()
            emptyList()
        }

        lua.register("get_localStorage") { args ->
            val rawUrl = args.getOrNull(0)?.optString() ?: return@register listOf(LuaValue.Nil)
            val host = try { URI(rawUrl).host ?: rawUrl } catch (_: Exception) { rawUrl }
            val key = args.getOrNull(1)?.optString() ?: return@register listOf(LuaValue.Nil)
            val prefs = context.getSharedPreferences("lua_localStorage", Context.MODE_PRIVATE)
            val json = prefs.getString(host, null) ?: return@register listOf(LuaValue.Nil)
            try {
                val map = gson.fromJson(json, Map::class.java)
                val value = map[key] as? String ?: return@register listOf(LuaValue.Nil)
                listOf(LuaValue.Str(value))
            } catch (_: Exception) {
                listOf(LuaValue.Nil)
            }
        }

        // Show Error
        lua.register("show_error") { args ->
            val title = args.getOrNull(0)?.optString() ?: ""
            val message = args.getOrNull(1)?.optString() ?: ""
            val authUrl = args.getOrNull(2)?.optString()
            setPendingShowError(title, message, authUrl)
            Timber.d("Native Lua show_error: title=%s message=%s authUrl=%s", title, message, authUrl)
            emptyList()
        }

        // Detect pagination
        lua.register("detect_pagination") { args ->
            val html = args.getOrNull(0)?.optString() ?: ""
            try {
                val next = Jsoup.parse(html).select("a[href]:contains(next), a[href]:contains(›), a[href]:contains(»)")
                val t = lua.newTable()
                t["hasNext"] = next.isNotEmpty()
                val nextUrl = next.firstOrNull()?.attr("abs:href")
                if (!nextUrl.isNullOrBlank()) {
                    t["next_url"] = nextUrl
                }
                listOf(t)
            } catch (_: Exception) {
                listOf(LuaValue.Nil)
            }
        }

        // Sleep & Logging
        lua.register("sleep") { args ->
            val ms = args.getOrNull(0)?.optLong(500L) ?: 500L
            try { Thread.sleep(ms) } catch (_: Exception) {}
            emptyList()
        }

        lua.register("log_info") { args ->
            val msg = args.getOrNull(0)?.optString() ?: ""
            Timber.i("LuaPlugin [%s]: %s", currentSourceId.get() ?: "global", msg)
            emptyList()
        }

        lua.register("log_error") { args ->
            val msg = args.getOrNull(0)?.optString() ?: ""
            Timber.e("LuaPlugin [%s]: %s", currentSourceId.get() ?: "global", msg)
            emptyList()
        }

        lua.register("os_time") {
            listOf(LuaValue.Integer(System.currentTimeMillis()))
        }
    }

    /**
     * Instantiates a fully functional [NativeLuaSourceAdapter] from Lua script source code.
     */
    public fun loadSourceFromScript(
        scriptContent: String,
        id: String,
        iconUrl: String? = null
    ): NativeLuaSourceAdapter {
        val lua = newSandboxState()
        val chunk = loadScriptChunk(lua, scriptContent, "=$id")
        lua.call(chunk)
        return NativeLuaSourceAdapter(context, lua, this, iconUrl, id)
    }
}
