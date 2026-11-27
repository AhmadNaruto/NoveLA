package com.github.anaruto.lua.novela

import com.github.anaruto.lua.*

/**
 * High-performance adapter bridging `lua-android` with the NoveLA extension specification.
 *
 * Provides drop-in support for:
 * - HTTP client bridging (`http_get`, `http_post`, `http_get_batch`)
 * - Response chaining (`response:toHtml()`, `response:to_html()`)
 * - Cookie management (`get_cookies`, `set_cookies`)
 * - System error dialogues (`show_error(title, msg, authUrl)`)
 * - Logging (`log_info`, `log_error`)
 * - Time and pause primitives (`os_time`, `sleep`)
 */
object NovelASupport {

    data class HttpResponse(
        val success: Boolean,
        val code: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap()
    )

    fun interface HttpHandler {
        fun request(method: String, url: String, body: String?, config: LuaTable?): HttpResponse
    }

    fun interface CookieHandler {
        fun getCookies(url: String): String
        fun setCookies(url: String, cookies: String) {}
    }

    fun interface ErrorHandler {
        fun showError(title: String, message: String, authUrl: String?)
    }

    interface PreferenceHandler {
        fun getPreference(key: String): String?
        fun setPreference(key: String, value: String)
        fun getLocalStorage(url: String, key: String): String? = null
    }

    interface LogHandler {
        fun info(tag: String, message: String)
        fun error(tag: String, message: String)
    }

    private val defaultLogHandler = object : LogHandler {
        override fun info(tag: String, message: String) {
            println("[$tag] $message")
        }

        override fun error(tag: String, message: String) {
            System.err.println("[$tag] ERROR: $message")
        }
    }

    private const val RESPONSE_METATABLE_KEY = "__novela_response_mt"

    private fun getOrCreateResponseMetatable(globals: Globals): LuaTable {
        val existing = globals.get(RESPONSE_METATABLE_KEY)
        if (existing.istable()) {
            return existing.checktable()
        }
        val mt = globals.newTable()
        val indexTable = globals.newTable()
        val toHtmlFunc = object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                val parseFunc = globals.get("html_parse")
                val bodyVal = arg1.get("body")
                val reqUrl = if (!arg2.isnil()) arg2 else arg1.get("_request_url")
                return if (!reqUrl.isnil()) {
                    parseFunc.call(bodyVal, reqUrl)
                } else {
                    parseFunc.call(bodyVal)
                }
            }
        }
        indexTable.set("toHtml", toHtmlFunc)
        indexTable.set("to_html", toHtmlFunc)
        mt.set(LuaValue.INDEX, indexTable)
        globals.set(RESPONSE_METATABLE_KEY, mt)
        return mt
    }

    /**
     * Builds a NoveLA-compliant HTTP response table with chaining capabilities (`toHtml`).
     */
    fun createResponseTable(globals: Globals, res: HttpResponse, requestUrl: String? = null): LuaTable {
        val table = globals.newTable()
        table.set("success", LuaValue.valueOf(res.success))
        table.set("code", LuaValue.valueOf(res.code))
        table.set("body", LuaValue.valueOf(res.body))
        if (requestUrl != null) {
            table.set("_request_url", LuaValue.valueOf(requestUrl))
        }

        val headersTable = globals.newTable()
        for ((k, v) in res.headers) {
            headersTable.set(k, LuaValue.valueOf(v))
        }
        table.set("headers", headersTable)

        val mt = getOrCreateResponseMetatable(globals)
        table.setmetatable(mt)

        return table
    }

    /**
     * Installs NoveLA runtime globals into the given [Globals] instance.
     */
    @JvmStatic
    @JvmOverloads
    fun install(
        globals: Globals,
        httpHandler: HttpHandler? = null,
        cookieHandler: CookieHandler? = null,
        errorHandler: ErrorHandler? = null,
        preferenceHandler: PreferenceHandler? = null,
        logHandler: LogHandler = defaultLogHandler
    ) {
        // --- HTTP GET ---
        globals.register("http_get") { args ->
            val url = args.checkjstring(1)
            val config = if (args.narg() >= 2 && args.arg(2).istable()) args.arg(2).checktable() else null
            if (httpHandler == null) {
                throw LuaError("http_get called but no HttpHandler is registered")
            }
            val res = httpHandler.request("GET", url, null, config)
            createResponseTable(globals, res, url)
        }

        // --- HTTP POST ---
        globals.register("http_post") { args ->
            val url = args.checkjstring(1)
            val body = if (args.narg() >= 2 && !args.arg(2).isnil()) args.arg(2).tojstring() else ""
            val config = if (args.narg() >= 3 && args.arg(3).istable()) args.arg(3).checktable() else null
            if (httpHandler == null) {
                throw LuaError("http_post called but no HttpHandler is registered")
            }
            val res = httpHandler.request("POST", url, body, config)
            createResponseTable(globals, res, url)
        }

        // --- HTTP GET BATCH ---
        globals.register("http_get_batch") { args ->
            val items = args.checktable(1)
            val config = if (args.narg() >= 2 && args.arg(2).istable()) args.arg(2).checktable() else null
            if (httpHandler == null) {
                throw LuaError("http_get_batch called but no HttpHandler is registered")
            }
            val resultTable = globals.newTable()
            val len = items.length()
            for (i in 1..len) {
                val urlVal = items.get(i)
                val url = urlVal.checkjstring()
                val res = httpHandler.request("GET", url, null, config)
                resultTable.set(i, createResponseTable(globals, res, url))
            }
            resultTable
        }

        // --- COOKIES ---
        globals.register("get_cookies") { args ->
            val url = args.checkjstring(1)
            val cookies = cookieHandler?.getCookies(url) ?: ""
            LuaValue.valueOf(cookies)
        }

        globals.register("set_cookies") { args ->
            val url = args.checkjstring(1)
            val cookies = args.checkjstring(2)
            cookieHandler?.setCookies(url, cookies)
            LuaValue.NIL
        }

        // --- LOGGING ---
        globals.register("log_info") { args ->
            logHandler.info("NoveLA", args.arg1().tojstring())
            LuaValue.NIL
        }

        globals.register("log_error") { args ->
            logHandler.error("NoveLA", args.arg1().tojstring())
            LuaValue.NIL
        }

        // --- PREFERENCES & WEBVIEW STORAGE ---
        globals.register("get_preference") { args ->
            val key = args.checkjstring(1)
            val v = preferenceHandler?.getPreference(key)
            if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
        }

        globals.register("set_preference") { args ->
            val key = args.checkjstring(1)
            val value = args.checkjstring(2)
            preferenceHandler?.setPreference(key, value)
            LuaValue.NIL
        }

        globals.register("get_localStorage") { args ->
            val url = args.checkjstring(1)
            val key = args.checkjstring(2)
            val v = preferenceHandler?.getLocalStorage(url, key)
            if (v != null) LuaValue.valueOf(v) else LuaValue.NIL
        }

        // --- ERROR & AUTH UI ---
        globals.register("show_error") { args ->
            val title = args.checkjstring(1)
            val msg = if (args.narg() >= 2) args.arg(2).tojstring() else ""
            val authUrl = if (args.narg() >= 3 && !args.arg(3).isnil()) args.arg(3).tojstring() else null
            errorHandler?.showError(title, msg, authUrl)
            LuaValue.NIL
        }

        // --- TIME & SLEEP ---
        globals.register("os_time") {
            LuaValue.valueOf(System.currentTimeMillis().toDouble())
        }

        globals.register("sleep") { args ->
            val ms = args.arg1().tolong()
            if (ms > 0) {
                try {
                    Thread.sleep(ms)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            LuaValue.NIL
        }
    }
}
