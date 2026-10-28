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
import my.noveldokusha.tooling.native_scraper.LuaNativeEngine
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

    private fun extractMetadata(): SourceMetadata {
        val helperScript = """
            function __novela_meta()
                return {
                    id = id or "",
                    name = name or "",
                    url = baseUrl or url or "",
                    language = language or "en",
                    charset = charset or "UTF-8",
                    contentType = contentType or "NOVEL",
                    icon = icon or "",
                    referer = referer or ""
                }
            end
        """.trimIndent()
        nativeEngine.execute(helperScript)
        val json = nativeEngine.callFunctionJson("__novela_meta")
        if (json != null && json != "null") {
            try {
                val obj = JsonParser.parseString(json).asJsonObject
                return SourceMetadata(
                    id = obj.get("id")?.asString.orEmpty().ifEmpty { fileName?.substringBeforeLast('.') ?: "native_source" },
                    name = obj.get("name")?.asString.orEmpty().ifEmpty { fileName?.substringBeforeLast('.') ?: "Native Source" },
                    url = obj.get("url")?.asString.orEmpty(),
                    language = obj.get("language")?.asString.orEmpty().ifEmpty { "en" },
                    charset = obj.get("charset")?.asString.orEmpty().ifEmpty { "UTF-8" },
                    contentType = obj.get("contentType")?.asString.orEmpty().ifEmpty { "NOVEL" },
                    icon = obj.get("icon")?.asString.orEmpty(),
                    referer = obj.get("referer")?.asString.orEmpty(),
                )
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse native metadata JSON: $json")
            }
        }
        return SourceMetadata(
            id = fileName?.substringBeforeLast('.') ?: "native_source",
            name = fileName?.substringBeforeLast('.') ?: "Native Source",
            url = "",
            language = "en",
            charset = "UTF-8",
            contentType = "NOVEL",
        )
    }

    private fun <T> checkShowError(): Response<T>? {
        val err = luaEngine.getPendingShowError() ?: return null
        return Response.Error(
            err.second,
            PluginShowErrorException(err.first, err.second, err.third)
        )
    }

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    luaEngine.resetShowError()
                    val json = nativeEngine.callFunctionJson("getCatalogList", index.toString())
                    checkShowError() ?: parseBooksFromJson(json)
                } catch (e: Exception) {
                    checkShowError() ?: Response.Error(e.message ?: "Native Lua getCatalogList error", e)
                }
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    luaEngine.resetShowError()
                    val json = nativeEngine.callFunctionJson("getCatalogSearch", index.toString(), input)
                    checkShowError() ?: parseBooksFromJson(json)
                } catch (e: Exception) {
                    checkShowError() ?: Response.Error(e.message ?: "Native Lua getCatalogSearch error", e)
                }
            }
        }

    override suspend fun getBookTitle(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    Response.Success(nativeEngine.callFunction("getBookTitle", bookUrl))
                } catch (e: Exception) {
                    Response.Error(e.message ?: "Native Lua getBookTitle error", e)
                }
            }
        }

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    Response.Success(nativeEngine.callFunction("getBookCoverImageUrl", bookUrl))
                } catch (e: Exception) {
                    Response.Error(e.message ?: "Native Lua getBookCoverImageUrl error", e)
                }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    Response.Success(nativeEngine.callFunction("getBookDescription", bookUrl))
                } catch (e: Exception) {
                    Response.Error(e.message ?: "Native Lua getBookDescription error", e)
                }
            }
        }

    override suspend fun getBookGenres(bookUrl: String): Response<List<String>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val json = nativeEngine.callFunctionJson("getBookGenres", bookUrl)
                    if (json == null || json == "null") return@withLock Response.Success(emptyList())
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

    override suspend fun getBookStatus(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    Response.Success(nativeEngine.callFunction("getBookStatus", bookUrl))
                } catch (e: Exception) {
                    Response.Error(e.message ?: "Native Lua getBookStatus error", e)
                }
            }
        }

    override suspend fun getBookLastUpdate(bookUrl: String): Response<String?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    Response.Success(nativeEngine.callFunction("getBookLastUpdate", bookUrl))
                } catch (e: Exception) {
                    Response.Error(e.message ?: "Native Lua getBookLastUpdate error", e)
                }
            }
        }

    override suspend fun getBookRating(bookUrl: String): Response<Float?> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val ratingStr = nativeEngine.callFunction("getBookRating", bookUrl)
                    Response.Success(ratingStr?.toFloatOrNull())
                } catch (e: Exception) {
                    Response.Error(e.message ?: "Native Lua getBookRating error", e)
                }
            }
        }

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    luaEngine.resetShowError()
                    val json = nativeEngine.callFunctionJson("getChapterList", bookUrl)
                    checkShowError() ?: parseChaptersFromJson(json)
                } catch (e: Exception) {
                    checkShowError() ?: Response.Error(e.message ?: "Native Lua getChapterList error", e)
                }
            }
        }

    override suspend fun getChapterText(chapterUrl: String): Response<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    luaEngine.resetShowError()
                    val text = nativeEngine.callFunction("getChapterText", chapterUrl)
                    checkShowError() ?: Response.Success(text.orEmpty())
                } catch (e: Exception) {
                    checkShowError() ?: Response.Error(e.message ?: "Native Lua getChapterText error", e)
                }
            }
        }

    override suspend fun getVideoList(chapterUrl: String): Response<List<VideoSource>> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    luaEngine.resetShowError()
                    val json = nativeEngine.callFunctionJson("getVideoList", chapterUrl)
                    checkShowError() ?: parseVideosFromJson(json)
                } catch (e: Exception) {
                    checkShowError() ?: Response.Error(e.message ?: "Native Lua getVideoList error", e)
                }
            }
        }

    private fun parseBooksFromJson(json: String?): Response<PagedList<BookResult>> {
        if (json == null || json == "null") return Response.Success(PagedList(emptyList(), false))
        return try {
            val element = JsonParser.parseString(json)
            if (element.isJsonArray) {
                val books = element.asJsonArray.mapNotNull { parseBookElement(it.asJsonObject) }
                Response.Success(PagedList(books, false))
            } else if (element.isJsonObject) {
                val obj = element.asJsonObject
                val listArr = obj.getAsJsonArray("list") ?: obj.getAsJsonArray("books") ?: JsonArray()
                val hasNext = obj.get("hasNextPage")?.asBoolean ?: (listArr.size() > 0)
                val books = listArr.mapNotNull { parseBookElement(it.asJsonObject) }
                Response.Success(PagedList(books, hasNext))
            } else {
                Response.Success(PagedList(emptyList(), false))
            }
        } catch (e: Exception) {
            Response.Error("Failed to parse catalog JSON: ${e.message}", e)
        }
    }

    private fun parseBookElement(obj: JsonObject): BookResult {
        return BookResult(
            title = obj.get("title")?.asString.orEmpty(),
            url = obj.get("url")?.asString.orEmpty(),
            coverImageUrl = obj.get("coverImageUrl")?.asString
                ?: obj.get("cover")?.asString
                ?: obj.get("image")?.asString.orEmpty(),
            status = obj.get("status")?.asString.orEmpty(),
        )
    }

    private fun parseChaptersFromJson(json: String?): Response<List<ChapterResult>> {
        if (json == null || json == "null") return Response.Success(emptyList())
        return try {
            val element = JsonParser.parseString(json)
            val arr = if (element.isJsonArray) element.asJsonArray else element.asJsonObject.getAsJsonArray("chapters") ?: JsonArray()
            val chapters = arr.mapIndexed { idx, item ->
                val obj = item.asJsonObject
                ChapterResult(
                    name = obj.get("title")?.asString
                        ?: obj.get("name")?.asString.orEmpty(),
                    url = obj.get("url")?.asString.orEmpty(),
                    date = obj.get("date")?.asString.orEmpty(),
                    order = obj.get("order")?.asInt ?: idx
                )
            }
            Response.Success(chapters)
        } catch (e: Exception) {
            Response.Error("Failed to parse chapters JSON: ${e.message}", e)
        }
    }

    private fun parseVideosFromJson(json: String?): Response<List<VideoSource>> {
        if (json == null || json == "null") return Response.Success(emptyList())
        return try {
            val element = JsonParser.parseString(json)
            val arr = if (element.isJsonArray) element.asJsonArray else JsonArray()
            val videos = arr.mapNotNull { item ->
                if (!item.isJsonObject) return@mapNotNull null
                val obj = item.asJsonObject
                VideoSource(
                    url = obj.get("url")?.asString.orEmpty(),
                    quality = obj.get("quality")?.asString.orEmpty().ifEmpty { "Default" }
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
