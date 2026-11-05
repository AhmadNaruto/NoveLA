package my.noveldokusha.scraper

import android.content.Context
import com.seanproctor.lua.LuaState
import com.seanproctor.lua.LuaValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.interceptors.PluginUARegistry
import my.noveldokusha.scraper.configs.SourceMetadata
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.domain.PluginShowErrorException
import my.noveldokusha.scraper.domain.VideoSource
import my.noveldokusha.scraper.domain.VideoSubtitle
import org.jsoup.nodes.Document
import timber.log.Timber
import java.net.URI
import java.util.Locale

/**
 * Adapter for Lua sources powered by the native Lua 5.4 engine (lua-kmp).
 * Replaces LuaJ with zero-copy JNI, Lexbor HTML5 DOM, and PCRE2 Regex.
 */
public open class NativeLuaSourceAdapter(
    protected val context: Context,
    protected val lua: LuaState,
    protected val engine: NativeLuaEngine,
    protected val iconUrlFromYaml: String? = null,
    protected val fileName: String? = null
) : SourceInterface.Catalog {

    protected val mutex: Mutex = Mutex()

    private val metadata: SourceMetadata = extractMetadata()

    override val id: String = metadata.id
    override val nameStrId: Int = 0
    override val name: String = metadata.name.ifEmpty { "Unknown" }
    override val baseUrl: String = metadata.url.ifEmpty {
        try { lua.getGlobal("baseUrl").optString("") ?: "" } catch (_: Exception) { "" }
    }
    override val catalogUrl: String = baseUrl
    override val charset: String = metadata.charset ?: "UTF-8"
    override val contentType: String get() = metadata.contentType
    override val referer: String = readReferer()

    override val language: LanguageCode? = when (metadata.language.lowercase(Locale.ROOT).trim()) {
        "mtl", "multi" -> LanguageCode.MTL
        else -> LanguageCode.values().find { it.iso639_1.equals(metadata.language, ignoreCase = true) }
            ?: LanguageCode.ENGLISH
    }

    override val languageTag: String? = metadata.language.takeIf { it.isNotBlank() }
    override val iconResId: Int? = null

    override val iconUrl: String? = iconUrlFromYaml
        ?: metadata.icon.takeIf { it.isNotEmpty() }?.let { icon ->
            if (icon.startsWith("http")) icon
            else "${baseUrl.trimEnd('/')}/$icon"
        }

    init {
        registerCfOptions()
        registerUAPreset()
    }

    private fun registerCfOptions() {
        if (baseUrl.isEmpty()) return
        val cfVal = try { lua.getGlobal("cf_options") } catch (_: Exception) { return }
        if (cfVal !is LuaValue.Table) return

        val whitelist = cfVal.getBoolean("whitelist", false)
        val ignoreMarkers = mutableSetOf<String>()
        val markersTable = cfVal["ignore_markers"] as? LuaValue.Table
        markersTable?.toStringList()?.let { ignoreMarkers.addAll(it) }

        val triggerMarkers = mutableSetOf<String>()
        val triggerTable = cfVal["trigger_markers"] as? LuaValue.Table
        triggerTable?.toStringList()?.let { triggerMarkers.addAll(it) }

        val host = try { URI(baseUrl).host ?: return } catch (_: Exception) { return }

        my.noveldokusha.network.interceptors.LuaCfOptionsRegistry.register(
            host,
            my.noveldokusha.network.interceptors.CfDomainOptions(
                whitelist = whitelist,
                ignoreMarkers = ignoreMarkers,
                triggerMarkers = triggerMarkers
            )
        )
    }

    private fun registerUAPreset() {
        val fn = try { lua.getGlobal("getUserAgentPreset") } catch (_: Exception) { return }
        if (fn !is LuaValue.Function) return
        val presetName = try {
            val res = lua.call(fn)
            res.getOrNull(0)?.optString()
        } catch (_: Exception) { null } ?: return
        if (presetName.isBlank()) return

        PluginUARegistry.register(id, presetName)
        if (baseUrl.isNotEmpty()) {
            try {
                val host = URI(baseUrl).host
                if (host != null) PluginUARegistry.registerHost(host, presetName)
            } catch (_: Exception) {}
        }
    }

    protected suspend fun <T> withSourceContext(block: suspend () -> T): T {
        engine.currentSourceId.set(id)
        try { return block() } finally { engine.currentSourceId.set(null) }
    }

    private fun readContentType(): String {
        val raw = try { lua.getGlobal("content_type").optString("") ?: "" } catch (_: Exception) { "" }
        return raw.lowercase(Locale.ROOT).takeIf { it == "manga" || it == "novel" || it == "video" } ?: ""
    }

    private fun readReferer(): String {
        val raw = try { lua.getGlobal("referer").optString("") ?: "" } catch (_: Exception) { "" }
        return raw.trim()
    }

    private fun extractMetadata(): SourceMetadata {
        fun s(key: String, def: String = ""): String = try {
            lua.getGlobal(key).optString(def) ?: def
        } catch (_: Exception) { def }

        return SourceMetadata(
            id = s("id", fileName?.let { "lua_$it" } ?: "lua_unknown"),
            name = s("name", "Unknown Source"),
            version = s("version", "1.0.0"),
            description = s("description"),
            url = s("baseUrl"),
            icon = s("icon"),
            language = s("language", "en"),
            charset = s("charset").takeIf { it.isNotBlank() },
            contentType = readContentType()
        )
    }

    protected fun checkShowError(): Response.Error? {
        val triple = engine.getPendingShowError() ?: return null
        val (title, message, authUrl) = triple
        return Response.Error(
            message = message,
            exception = PluginShowErrorException(title, message, authUrl),
            pluginErrorTitle = title,
            pluginErrorMessage = message,
            pluginAuthUrl = authUrl
        )
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    engine.resetShowError()
                    try {
                        val fn = lua.getGlobal("getCatalogList") as? LuaValue.Function
                            ?: return@withSourceContext Response.Error("getCatalogList not found")
                        val results = engine.withForceNetwork {
                            lua.call(fn, listOf(LuaValue.Integer(index.toLong())))
                        }
                        checkShowError() ?: convertLuaResultToPagedList(results.getOrNull(0))
                    } catch (e: Exception) {
                        checkShowError() ?: run {
                            Timber.e(e, "NativeLua getCatalogList [$id]")
                            Response.Error(e.message ?: "Native Lua error", e)
                        }
                    }
                }
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    engine.resetShowError()
                    try {
                        val fn = lua.getGlobal("getCatalogSearch") as? LuaValue.Function
                            ?: return@withSourceContext Response.Error("getCatalogSearch not found")
                        val results = lua.call(fn, listOf(LuaValue.Integer(index.toLong()), LuaValue.Str(input)))
                        checkShowError() ?: convertLuaResultToPagedList(results.getOrNull(0))
                    } catch (e: Exception) {
                        checkShowError() ?: run {
                            Timber.e(e, "NativeLua getCatalogSearch [$id]")
                            Response.Error(e.message ?: "Native Lua error", e)
                        }
                    }
                }
            }
        }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookTitle") as? LuaValue.Function
                            ?: return@withSourceContext Response.Error("getBookTitle not found")
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString())
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookTitle [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookCoverImageUrl") as? LuaValue.Function
                            ?: return@withSourceContext Response.Error("getBookCoverImageUrl not found")
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString())
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookCoverImageUrl [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookDescription") as? LuaValue.Function
                            ?: return@withSourceContext Response.Error("getBookDescription not found")
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString())
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookDescription [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getBookGenres(bookUrl: String): Response<List<String>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookGenres") as? LuaValue.Function
                            ?: return@withSourceContext Response.Success(emptyList())
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        val table = res as? LuaValue.Table ?: return@withSourceContext Response.Success(emptyList())
                        Response.Success(table.toStringList())
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookGenres [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getBookRating(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookRating") as? LuaValue.Function
                            ?: return@withSourceContext Response.Success(null)
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString()?.takeIf { it.isNotBlank() })
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookRating [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getBookStatus(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookStatus") as? LuaValue.Function
                            ?: return@withSourceContext Response.Success(null)
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString()?.takeIf { it.isNotBlank() })
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookStatus [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getBookLastUpdate(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getBookLastUpdate") as? LuaValue.Function
                            ?: return@withSourceContext Response.Success(null)
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString()?.takeIf { it.isNotBlank() })
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getBookLastUpdate [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    engine.resetShowError()
                    try {
                        val fn = lua.getGlobal("getChapterList") as? LuaValue.Function
                            ?: return@withSourceContext Response.Error("getChapterList not found")
                        val results = lua.call(fn, listOf(LuaValue.Str(bookUrl)))
                        checkShowError() ?: run {
                            val chapters = mutableListOf<ChapterResult>()
                            val table = results.getOrNull(0) as? LuaValue.Table
                            if (table != null) {
                                for (i in 1..table.size) {
                                    val ch = table[i] as? LuaValue.Table
                                    if (ch != null) chapters.add(convertLuaTableToChapterResult(ch))
                                }
                            }
                            Response.Success(chapters)
                        }
                    } catch (e: Exception) {
                        checkShowError() ?: run {
                            Timber.e(e, "NativeLua getChapterList [$id]")
                            Response.Error(e.message ?: "Native Lua error", e)
                        }
                    }
                }
            }
        }

    override suspend fun parsePage(
        bookUrl: String,
        page: Int
    ): Response<SourceInterface.Catalog.PagedChapterResult>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val fn = lua.getGlobal("parsePage") as? LuaValue.Function ?: return@withLock null
                withSourceContext {
                    engine.resetShowError()
                    try {
                        val results = lua.call(fn, listOf(LuaValue.Str(bookUrl), LuaValue.Integer(page.toLong())))
                        checkShowError() ?: run {
                            val table = results.getOrNull(0) as? LuaValue.Table
                                ?: return@withSourceContext Response.Error("parsePage returned non-table", Exception())
                            val chaptersTable = table["chapters"] as? LuaValue.Table
                            val chapters = mutableListOf<ChapterResult>()
                            if (chaptersTable != null) {
                                for (i in 1..chaptersTable.size) {
                                    val ch = chaptersTable[i] as? LuaValue.Table
                                    if (ch != null) chapters.add(convertLuaTableToChapterResult(ch))
                                }
                            }
                            val totalPages = table.getInt("totalPages", 1)
                            Response.Success(
                                SourceInterface.Catalog.PagedChapterResult(
                                    chapters = chapters,
                                    totalPages = totalPages
                                )
                            )
                        }
                    } catch (e: Exception) {
                        checkShowError() ?: run {
                            Timber.e(e, "NativeLua parsePage [$id] page=$page")
                            Response.Error(e.message ?: "Native Lua error", e)
                        }
                    }
                }
            }
        }

    override suspend fun getChapterText(doc: Document): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                engine.resetShowError()
                withSourceContext {
                    val html = doc.outerHtml()
                    val url = doc.location()
                    val fn = lua.getGlobal("getChapterText") as? LuaValue.Function ?: return@withSourceContext null
                    val res = lua.call(fn, listOf(LuaValue.Str(html), LuaValue.Str(url))).getOrNull(0)
                    engine.getPendingShowError()?.let { (title, message, authUrl) ->
                        throw PluginShowErrorException(title, message, authUrl)
                    }
                    res?.optString()
                }
            }
        }

    override suspend fun getChapterPages(doc: Document): List<String>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                engine.resetShowError()
                withSourceContext {
                    val fn = lua.getGlobal("getPageList") as? LuaValue.Function ?: return@withSourceContext null
                    val res = try {
                        lua.call(fn, listOf(LuaValue.Str(doc.outerHtml()), LuaValue.Str(doc.location()))).getOrNull(0)
                    } catch (e: Exception) {
                        engine.getPendingShowError()?.let { (title, message, authUrl) ->
                            throw PluginShowErrorException(title, message, authUrl)
                        }
                        Timber.e(e, "NativeLua getPageList [$id]")
                        return@withSourceContext emptyList()
                    }
                    engine.getPendingShowError()?.let { (title, message, authUrl) ->
                        throw PluginShowErrorException(title, message, authUrl)
                    }
                    val table = res as? LuaValue.Table ?: return@withSourceContext emptyList()
                    table.toStringList()
                }
            }
        }

    override suspend fun getVideoList(episodeUrl: String): Response<List<VideoSource>>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    engine.resetShowError()
                    val fn = lua.getGlobal("getVideoList") as? LuaValue.Function ?: return@withSourceContext null
                    try {
                        val results = lua.call(fn, listOf(LuaValue.Str(episodeUrl)))
                        checkShowError() ?: convertLuaVideoList(results.getOrNull(0))
                    } catch (e: Exception) {
                        checkShowError() ?: run {
                            Timber.e(e, "NativeLua getVideoList [$id]")
                            Response.Error(e.message ?: "Native Lua error", e)
                        }
                    }
                }
            }
        }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        val fn = lua.getGlobal("getChapterListHash") as? LuaValue.Function
                            ?: return@withSourceContext Response.Success(null)
                        val res = lua.call(fn, listOf(LuaValue.Str(bookUrl))).getOrNull(0)
                        Response.Success(res?.optString())
                    } catch (e: Exception) {
                        Timber.e(e, "NativeLua getChapterListHash [$id]")
                        Response.Error(e.message ?: "Native Lua error", e)
                    }
                }
            }
        }

    // ── Data Mapping Helpers ──────────────────────────────────────────────────

    protected fun convertLuaResultToPagedList(luaResult: LuaValue?): Response<PagedList<BookResult>> {
        val table = luaResult as? LuaValue.Table ?: return Response.Success(PagedList(emptyList(), 0, true))
        return try {
            val items = mutableListOf<BookResult>()
            val itemsTable = table["items"] as? LuaValue.Table
            if (itemsTable != null) {
                for (i in 1..itemsTable.size) {
                    val item = itemsTable[i] as? LuaValue.Table
                    if (item != null) items.add(convertLuaTableToBookResult(item))
                }
            }
            val hasNext = table.getBoolean("hasNext", false)
            Response.Success(PagedList(items, 0, !hasNext))
        } catch (e: Exception) {
            Timber.e(e, "NativeLua convertLuaResultToPagedList failed")
            Response.Error(e.message ?: "Conversion error", e)
        }
    }

    internal fun convertLuaTableToBookResult(table: LuaValue.Table) = BookResult(
        title = table.getString("title", "") ?: "",
        url = table.getString("url", "") ?: "",
        coverImageUrl = table.getString("cover", "") ?: "",
        rating = table["rating"].optString()?.takeIf { it.isNotBlank() },
        contentType = readContentType()
    )

    private fun convertLuaTableToChapterResult(table: LuaValue.Table) = ChapterResult(
        title = table.getString("title", "") ?: "",
        url = table.getString("url", "") ?: "",
        volume = table.getString("volume"),
        uploaded = (table["uploaded"] as? LuaValue.Integer)?.value
    )

    private fun normalizeVideoMime(value: LuaValue?, itemIndex: Int): String? {
        val raw = value?.optString()?.trim() ?: return null
        if (raw.isEmpty()) return null
        val mime = raw.lowercase(Locale.ROOT)
        return when (mime) {
            "hls", "m3u8",
            "application/x-mpegurl",
            "application/vnd.apple.mpegurl" -> "application/x-mpegURL"
            "mp4", "video/mp4" -> "video/mp4"
            "mpd", "application/dash+xml" -> "application/dash+xml"
            else -> mime.takeIf {
                it.startsWith("video/") ||
                it == "application/vnd.ms-sstr+xml" ||
                it == "application/x-rtsp"
            }
        }
    }

    internal fun convertLuaVideoList(luaResult: LuaValue?): Response<List<VideoSource>> {
        if (luaResult == null || luaResult.isNil()) return Response.Success(emptyList())
        val table = luaResult as? LuaValue.Table ?: return Response.Error("getVideoList must return table")
        val videos = mutableListOf<VideoSource>()
        for (i in 1..table.size) {
            val item = table[i] as? LuaValue.Table ?: continue
            val url = item.getString("url", "") ?: ""
            if (url.isBlank()) continue
            val quality = item.getString("quality", "Default") ?: "Default"
            val mime = normalizeVideoMime(item["mime"], i)
            val headersMap = mutableMapOf<String, String>()
            val hTable = item["headers"] as? LuaValue.Table
            hTable?.toMap()?.forEach { (k, v) ->
                val kStr = k.optString()
                val vStr = v.optString()
                if (!kStr.isNullOrBlank() && vStr != null) headersMap[kStr] = vStr
            }

            val subtitlesList = mutableListOf<VideoSubtitle>()
            val sTable = item["subtitles"] as? LuaValue.Table
            if (sTable != null) {
                for (j in 1..sTable.size) {
                    val sub = sTable[j] as? LuaValue.Table ?: continue
                    val sUrl = sub.getString("url", "") ?: ""
                    if (sUrl.isBlank()) continue
                    subtitlesList.add(
                        VideoSubtitle(
                            url = sUrl,
                            label = sub.getString("label", "") ?: "",
                            lang = sub.getString("lang", "") ?: ""
                        )
                    )
                }
            }

            videos.add(
                VideoSource(
                    url = url,
                    quality = quality,
                    headers = headersMap,
                    mime = mime,
                    subtitles = subtitlesList
                )
            )
        }
        return Response.Success(videos)
    }
}
