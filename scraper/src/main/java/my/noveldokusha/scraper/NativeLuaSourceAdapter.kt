package my.noveldokusha.scraper

import android.content.Context
import androidx.compose.runtime.Composable
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.scraper.configs.SourceMetadata
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import my.noveldokusha.scraper.domain.PluginShowErrorException
import my.noveldokusha.scraper.domain.VideoSource
import my.noveldokusha.scraper.domain.VideoSubtitle
import my.noveldokusha.tooling.native_scraper.LuaNativeEngine
import org.jsoup.nodes.Document
import timber.log.Timber
import java.io.Closeable

/**
 * High-performance native Lua adapter executing scrapers via Lua 5.4 C engine and Lexbor.
 * Eliminates JVM garbage collection overhead by keeping DOM trees and Lua VM stacks in native heap.
 */
open class NativeLuaSourceAdapter(
    protected val context: Context,
    protected val scriptContent: String,
    protected val luaEngine: LuaEngine,
    protected val iconUrlFromYaml: String? = null,
    protected val fileName: String? = null,
) : SourceInterface.Catalog, Closeable {

    protected val mutex = Mutex()
    protected val nativeEngine = LuaNativeEngine(bridge = luaEngine)

    private val metadata: SourceMetadata

    init {
        nativeEngine.execute(scriptContent)
        metadata = extractMetadata()
    }

    override val id: String get() = metadata.id
    override val nameStrId: Int = 0
    override val name: String get() = metadata.name.ifEmpty { "Unknown" }
    override val baseUrl: String get() = metadata.url
    override val catalogUrl: String get() = baseUrl
    override val charset: String get() = metadata.charset ?: "UTF-8"
    override val contentType: String get() = metadata.contentType
    override val referer: String get() = metadata.referer

    override val language: LanguageCode? = when (metadata.language.lowercase().trim()) {
        "mtl", "multi" -> LanguageCode.MTL
        else -> LanguageCode.entries.find { it.iso639_1.equals(metadata.language, ignoreCase = true) }
            ?: LanguageCode.ENGLISH
    }

    override val languageTag: String? = metadata.language.takeIf { it.isNotBlank() }
    override val iconResId: Int? = null

    override val iconUrl: String? = iconUrlFromYaml
        ?: metadata.icon.takeIf { it.isNotEmpty() }?.let { icon ->
            if (icon.startsWith("http")) icon
            else "${baseUrl.trimEnd('/')}/$icon"
        }

    protected suspend fun <T> withSourceContext(block: suspend () -> T): T {
        luaEngine.currentSourceId.set(id)
        try { return block() } finally { luaEngine.currentSourceId.set(null) }
    }

    private fun extractMetadata(): SourceMetadata {
        val helperScript = """
            function __novela_meta()
                return {
                    id = id or "",
                    name = name or "",
                    baseUrl = baseUrl or url or "",
                    language = language or "en",
                    charset = charset or "UTF-8",
                    content_type = content_type or contentType or "",
                    icon = icon or "",
                    referer = referer or "",
                    version = version or "1.0.0"
                }
            end
        """.trimIndent()
        nativeEngine.execute(helperScript)
        val json = nativeEngine.call("__novela_meta")
        if (json != null && json != "null") {
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                val rawContentType = obj.get("content_type")?.asString.orEmpty().lowercase()
                val ct = rawContentType.takeIf { it == "manga" || it == "novel" || it == "video" } ?: ""
                return SourceMetadata(
                    id = obj.get("id")?.asString.orEmpty().ifEmpty { fileName?.let { "lua_$it" } ?: "lua_native" },
                    name = obj.get("name")?.asString.orEmpty().ifEmpty { fileName?.substringBeforeLast('.') ?: "Native Source" },
                    url = obj.get("baseUrl")?.asString.orEmpty(),
                    language = obj.get("language")?.asString.orEmpty().ifEmpty { "en" },
                    charset = obj.get("charset")?.asString.orEmpty().ifEmpty { "UTF-8" },
                    contentType = ct,
                    icon = obj.get("icon")?.asString.orEmpty(),
                    referer = obj.get("referer")?.asString.orEmpty().trim(),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse native metadata JSON: $json")
            }
        }
        return SourceMetadata(
            id = fileName?.let { "lua_$it" } ?: "lua_native",
            name = fileName?.substringBeforeLast('.') ?: "Native Source",
            url = "",
            language = "en",
            charset = "UTF-8",
            contentType = "",
        )
    }

    protected fun checkShowError(): Response.Error? {
        val triple = luaEngine.getPendingShowError() ?: return null
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
                    luaEngine.resetShowError()
                    try {
                        val json = luaEngine.withForceNetwork {
                            nativeEngine.call("getCatalogList", index)
                        }
                        checkShowError() ?: parseBooksFromJson(json, index)
                    } catch (e: Exception) {
                        checkShowError() ?: Response.Error(e.message ?: "Native Lua getCatalogList error", e)
                    }
                }
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    luaEngine.resetShowError()
                    try {
                        val json = luaEngine.withForceNetwork {
                            nativeEngine.call("getCatalogSearch", index, input)
                        }
                        checkShowError() ?: parseBooksFromJson(json, index)
                    } catch (e: Exception) {
                        checkShowError() ?: Response.Error(e.message ?: "Native Lua getCatalogSearch error", e)
                    }
                }
            }
        }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        Response.Success(nativeEngine.call("getBookTitle", bookUrl))
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookTitle error", e)
                    }
                }
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        Response.Success(nativeEngine.call("getBookCoverImageUrl", bookUrl))
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookCoverImageUrl error", e)
                    }
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        Response.Success(nativeEngine.call("getBookDescription", bookUrl))
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookDescription error", e)
                    }
                }
            }
        }

    override suspend fun getBookGenres(bookUrl: String): Response<List<String>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        if (!nativeEngine.hasFunction("getBookGenres")) return@withSourceContext Response.Success(emptyList())
                        val json = nativeEngine.call("getBookGenres", bookUrl)
                        if (json.isNullOrBlank() || json == "null") return@withSourceContext Response.Success(emptyList())
                        val parsed = JsonParser.parseString(json)
                        if (parsed.isJsonArray) {
                            Response.Success(parsed.asJsonArray.mapNotNull { it.asString })
                        } else {
                            Response.Success(emptyList())
                        }
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookGenres error", e)
                    }
                }
            }
        }

    override suspend fun getBookStatus(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        if (!nativeEngine.hasFunction("getBookStatus")) return@withSourceContext Response.Success(null)
                        val status = nativeEngine.call("getBookStatus", bookUrl)?.takeIf { it.isNotBlank() }
                        Response.Success(status)
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookStatus error", e)
                    }
                }
            }
        }

    override suspend fun getBookLastUpdate(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        if (!nativeEngine.hasFunction("getBookLastUpdate")) return@withSourceContext Response.Success(null)
                        val lastUpdate = nativeEngine.call("getBookLastUpdate", bookUrl)?.takeIf { it.isNotBlank() }
                        Response.Success(lastUpdate)
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookLastUpdate error", e)
                    }
                }
            }
        }

    override suspend fun getBookRating(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        if (!nativeEngine.hasFunction("getBookRating")) return@withSourceContext Response.Success(null)
                        val rating = nativeEngine.call("getBookRating", bookUrl)?.takeIf { it.isNotBlank() }
                        Response.Success(rating)
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getBookRating error", e)
                    }
                }
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    try {
                        luaEngine.resetShowError()
                        val json = nativeEngine.call("getChapterList", bookUrl)
                        checkShowError() ?: parseChaptersFromJson(json)
                    } catch (e: Exception) {
                        checkShowError() ?: Response.Error(e.message ?: "Native Lua getChapterList error", e)
                    }
                }
            }
        }

    override suspend fun getChapterText(doc: Document): String? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    luaEngine.resetShowError()
                    val html = doc.outerHtml()
                    val url = doc.location()
                    val result = nativeEngine.call("getChapterText", html, url)
                    luaEngine.getPendingShowError()?.let { (title, message, authUrl) ->
                        throw PluginShowErrorException(title, message, authUrl)
                    }
                    result
                }
            }
        }

    override suspend fun getChapterPages(doc: Document): List<String>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    if (!nativeEngine.hasFunction("getPageList")) return@withSourceContext null
                    luaEngine.resetShowError()
                    try {
                        val html = doc.outerHtml()
                        val url = doc.location()
                        val json = nativeEngine.call("getPageList", html, url)
                        luaEngine.getPendingShowError()?.let { (title, message, authUrl) ->
                            throw PluginShowErrorException(title, message, authUrl)
                        }
                        if (json.isNullOrBlank() || json == "null") return@withSourceContext emptyList()
                        val element = JsonParser.parseString(json)
                        if (element.isJsonArray) {
                            element.asJsonArray.mapNotNull { it.asString.takeIf { s -> s.isNotBlank() } }
                        } else emptyList()
                    } catch (e: Exception) {
                        luaEngine.getPendingShowError()?.let { (title, message, authUrl) ->
                            throw PluginShowErrorException(title, message, authUrl)
                        }
                        Timber.e(e, "NativeLua getPageList error [$id]")
                        emptyList()
                    }
                }
            }
        }

    override suspend fun getVideoList(episodeUrl: String): Response<List<VideoSource>>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    if (!nativeEngine.hasFunction("getVideoList")) return@withSourceContext null
                    luaEngine.resetShowError()
                    try {
                        val json = nativeEngine.call("getVideoList", episodeUrl)
                        checkShowError() ?: parseVideosFromJson(json)
                    } catch (e: Exception) {
                        checkShowError() ?: Response.Error(e.message ?: "Native Lua getVideoList error", e)
                    }
                }
            }
        }

    override suspend fun getChapterListHash(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    if (!nativeEngine.hasFunction("getChapterListHash")) return@withSourceContext Response.Success(null)
                    try {
                        val hash = nativeEngine.call("getChapterListHash", bookUrl)
                        Response.Success(hash)
                    } catch (e: Exception) {
                        Response.Error(e.message ?: "Native Lua getChapterListHash error", e)
                    }
                }
            }
        }

    override suspend fun parsePage(bookUrl: String, page: Int): Response<SourceInterface.Catalog.PagedChapterResult>? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                withSourceContext {
                    if (!nativeEngine.hasFunction("parsePage")) return@withSourceContext null
                    luaEngine.resetShowError()
                    try {
                        val json = nativeEngine.call("parsePage", bookUrl, page)
                        val err = checkShowError()
                        if (err != null) return@withSourceContext err
                        if (json.isNullOrBlank() || json == "null") {
                            return@withSourceContext Response.Success(
                                SourceInterface.Catalog.PagedChapterResult(emptyList(), 1)
                            )
                        }
                        val element = JsonParser.parseString(json)
                        if (!element.isJsonObject) {
                            return@withSourceContext Response.Success(
                                SourceInterface.Catalog.PagedChapterResult(emptyList(), 1)
                            )
                        }
                        val obj = element.asJsonObject
                        val chaptersRes = parseChaptersFromJson(obj.get("chapters")?.toString())
                        val chapters = when (chaptersRes) {
                            is Response.Success -> chaptersRes.data
                            is Response.Error -> emptyList()
                        }
                        val totalPages = obj.get("totalPages")?.asInt ?: 1
                        Response.Success(SourceInterface.Catalog.PagedChapterResult(chapters, totalPages))
                    } catch (e: Exception) {
                        checkShowError() ?: Response.Error(e.message ?: "Native Lua parsePage error", e)
                    }
                }
            }
        }

    private fun parseBooksFromJson(json: String?, pageIndex: Int): Response<PagedList<BookResult>> {
        if (json.isNullOrBlank() || json == "null") return Response.Success(PagedList(emptyList(), pageIndex, true))
        return try {
            val element = JsonParser.parseString(json)
            val items = mutableListOf<BookResult>()
            var hasNext = false
            if (element.isJsonObject) {
                val obj = element.asJsonObject
                val itemsArr = obj.getAsJsonArray("items") ?: obj.getAsJsonArray("list")
                if (itemsArr != null) {
                    for (item in itemsArr) {
                        if (item.isJsonObject) items.add(parseBookElement(item.asJsonObject))
                    }
                }
                hasNext = obj.get("hasNext")?.asBoolean ?: false
            } else if (element.isJsonArray) {
                for (item in element.asJsonArray) {
                    if (item.isJsonObject) items.add(parseBookElement(item.asJsonObject))
                }
            }
            Response.Success(PagedList(items, pageIndex, !hasNext))
        } catch (e: Exception) {
            Response.Error("Failed to parse catalog JSON: ${e.message}", e)
        }
    }

    private fun parseBookElement(obj: JsonObject): BookResult {
        return BookResult(
            title = obj.get("title")?.asString.orEmpty(),
            url = obj.get("url")?.asString.orEmpty(),
            coverImageUrl = obj.get("cover")?.asString
                ?: obj.get("coverImageUrl")?.asString
                ?: obj.get("image")?.asString.orEmpty(),
            description = obj.get("description")?.asString.orEmpty(),
            rating = obj.get("rating")?.let {
                if (it.isJsonNull) null
                else if (it.isJsonPrimitive) it.asString.takeIf { s -> s.isNotBlank() }
                else null
            },
            contentType = metadata.contentType
        )
    }

    private fun parseChaptersFromJson(json: String?): Response<List<ChapterResult>> {
        if (json.isNullOrBlank() || json == "null") return Response.Success(emptyList())
        return try {
            val element = JsonParser.parseString(json)
            val arr = if (element.isJsonArray) {
                element.asJsonArray
            } else if (element.isJsonObject) {
                element.asJsonObject.getAsJsonArray("chapters") ?: JsonArray()
            } else {
                JsonArray()
            }
            val chapters = mutableListOf<ChapterResult>()
            for (item in arr) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val title = obj.get("title")?.asString.orEmpty()
                val url = obj.get("url")?.asString.orEmpty()
                val volume = obj.get("volume")?.takeIf { !it.isJsonNull }?.asString
                val uploaded = obj.get("uploaded")?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                chapters.add(ChapterResult(title = title, url = url, volume = volume, uploaded = uploaded))
            }
            Response.Success(chapters)
        } catch (e: Exception) {
            Response.Error("Failed to parse chapters JSON: ${e.message}", e)
        }
    }

    private fun normalizeVideoMime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val mime = raw.trim().lowercase()
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

    private fun parseVideosFromJson(json: String?): Response<List<VideoSource>> {
        if (json.isNullOrBlank() || json == "null") return Response.Success(emptyList())
        return try {
            val element = JsonParser.parseString(json)
            val arr = if (element.isJsonArray) element.asJsonArray else JsonArray()
            val videos = arr.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val obj = item.asJsonObject
                val url = obj.get("url")?.asString.orEmpty()
                if (url.isBlank()) return@mapNotNull null
                val quality = obj.get("quality")?.asString.orEmpty()
                val headers = mutableMapOf<String, String>()
                obj.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                    headers[k] = v.asString
                }
                val subtitles = mutableListOf<VideoSubtitle>()
                obj.getAsJsonArray("subtitles")?.forEach { sItem ->
                    if (sItem.isJsonObject) {
                        val sObj = sItem.asJsonObject
                        val sUrl = sObj.get("url")?.asString.orEmpty()
                        if (sUrl.isNotBlank()) {
                            subtitles.add(
                                VideoSubtitle(
                                    url = sUrl,
                                    label = sObj.get("label")?.asString.orEmpty(),
                                    lang = sObj.get("lang")?.asString.orEmpty()
                                )
                            )
                        }
                    }
                }
                val mime = normalizeVideoMime(obj.get("mime")?.asString)
                VideoSource(
                    url = url,
                    quality = quality,
                    headers = headers,
                    subtitles = subtitles,
                    mime = mime
                )
            }
            Response.Success(videos)
        } catch (e: Exception) {
            Response.Error("Failed to parse videos JSON: ${e.message}", e)
        }
    }

    override fun close() {
        nativeEngine.close()
    }
}
