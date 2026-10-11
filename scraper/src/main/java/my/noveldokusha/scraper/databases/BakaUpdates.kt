package my.noveldokusha.scraper.databases

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response.Success
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryFlatConnect
import my.noveldokusha.scraper.DatabaseInterface
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SearchGenre
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.tooling.native_scraper.LexborJsoupFacade

class BakaUpdates(
    private val networkClient: NetworkClient
) : DatabaseInterface {
    override val id = "baka_updates"
    override val nameStrId = R.string.database_name_baka_updates
    override val baseUrl = "https://www.mangaupdates.com/"
    override val iconUrl = "https://raw.githubusercontent.com/HnDK0/external-sources/main/icons/manga-updates.png"

    private fun String.removeNovelTag() = this.removeSuffix("(Novel)").trim()

    override suspend fun getCatalog(index: Int) = withContext(Dispatchers.Default) {
        val page = index + 1
        val url = "https://www.mangaupdates.com/series.html".toUrlBuilderSafe().apply {
            if (page > 1) add("page", page)
            add("type", "novel")
            add("perpage", 50)
            add("orderby", "rating")
        }
        getSearchList(page, url)
    }

    override suspend fun getAuthorData(authorUrl: String) = withContext(Dispatchers.Default) {
        tryFlatConnect {
            val response = networkClient.get(authorUrl)
            val doc = LexborJsoupFacade.parse(response.body.string(), authorUrl)
            fun entry(header: String) =
                doc.select("div.sCat > b")
                    .find { it.text().trim() == header }
                    ?.parent()?.nextElementSibling()

            val coverImageUrl = entry("Image")
                ?.selectFirst("img[src]")?.attr("src") ?: ""
            val description = entry("Comments")?.text() ?: ""
            val genres = entry("Genres")?.select("a")?.dropLast(1)?.map { it.text() } ?: emptyList()
            val books = doc
                .select(".pl-2.col-md-5.col-7.text-truncate.text > a[href]")
                .filter { it -> it.text().endsWith("(Novel)") }
                .map { BookResult(title = it.text().removeNovelTag(), url = it.attr("href")) }
            val name = doc.selectFirst(".releasestitle.tabletitle")?.text()?.removeNovelTag() ?: ""
            val associatedNames = TextExtractor.get(entry("Associated Names")).split("\n\n")

            DatabaseInterface.AuthorData(
                name = name,
                description = description,
                associatedNames = associatedNames,
                books = books,
                genres = genres,
                coverImageUrl = coverImageUrl
            ).let(::Success)
        }
    }

    override suspend fun getSearchFilters() = withContext(Dispatchers.Default) {
        tryFlatConnect {
            val response = networkClient.get("https://www.mangaupdates.com/series.html?act=genresearch")
            val doc = LexborJsoupFacade.parse(response.body.string(), "https://www.mangaupdates.com/series.html?act=genresearch")
            return@tryFlatConnect doc
                .select(".p-1.col-6.text")
                .map { it.text().trim() }
                .associateWith { it.replace(" ", "+") }
                .map { (id, genre) ->
                    SearchGenre(
                        id = id,
                        genreName = genre
                    )
                }
                .let { Success(it) }
        }
    }

    override suspend fun searchByTitle(
        index: Int,
        input: String
    ) = withContext(Dispatchers.Default) {
        val page = index + 1
        val url = "https://www.mangaupdates.com/series.html".toUrlBuilderSafe().apply {
            if (page > 1) add("page", page)
            add("perpage", 50)
            add("type", "novel")
            add("search", input)
        }

        getSearchList(page, url)
    }

    override suspend fun searchByFilters(
        index: Int,
        genresIncludedId: List<String>,
        genresExcludedId: List<String>
    ) = withContext(Dispatchers.Default) {
        val page = index + 1
        val url = "https://www.mangaupdates.com/series.html".toUrlBuilderSafe().apply {
            if (page > 1) add("page", page)
            if (genresIncludedId.isNotEmpty()) add("genre", genresIncludedId.joinToString("_"))
            if (genresExcludedId.isNotEmpty()) add(
                "exclude_genre",
                genresExcludedId.joinToString("_")
            )
            add("type", "novel")
            add("perpage", 50)
        }

        getSearchList(index, url)
    }

    private suspend fun getSearchList(
        index: Int,
        url: Uri.Builder
    ) = withContext(Dispatchers.Default) {
        tryFlatConnect("index: $index\n\nurl: $url") {
            val response = networkClient.get(url)
            val doc = LexborJsoupFacade.parse(response.body.string(), url.toString())
            doc.select(".col-12.col-lg-6.p-3.text")
                .mapNotNull {
                    val link = it.selectFirst("div.text > a[href]") ?: return@mapNotNull null
                    val bookCover = it.selectFirst("img[src]")?.attr("src") ?: ""
                    val description =
                        it.selectFirst("div.text.flex-grow-1")?.text()?.removeNovelTag() ?: ""
                    BookResult(
                        title = link.text().removeNovelTag(),
                        url = link.attr("href"),
                        coverImageUrl = bookCover,
                        description = description
                    )
                }
                .let {
                    val isLastPage = doc.select("a[href]").none { it.text().contains("Next Page") }
                    Success(
                        PagedList(
                            list = it,
                            index = index,
                            isLastPage = isLastPage
                        )
                    )
                }
        }
    }

    override suspend fun getBookData(
        bookUrl: String
    ) = withContext(Dispatchers.Default) {
        tryFlatConnect {
            val response = networkClient.get(bookUrl)
            val doc = LexborJsoupFacade.parse(response.body.string(), bookUrl)
            fun entry(header: String) =
                doc.select("div.sCat > b")
                    .find { it.text().trim() == header }
                    ?.parent()?.nextElementSibling()

            val relatedBooks = entry("Category Recommendations")
                ?.select("a[href]")
                ?.map {
                    BookResult(
                        title = it.text().removeNovelTag(),
                        url = it.attr("href")
                    )
                }?.toList() ?: emptyList()

            val similarRecommended = entry("Recommendations")
                ?.select("a[href]")
                ?.map {
                    BookResult(
                        title = it.text().removeNovelTag(),
                        url = it.attr("href")
                    )
                }?.toList() ?: emptyList()

            val authors = entry("Author\\(s\\)")
                ?.select("a[href]")
                ?.mapNotNull {
                    val url = it.attr("href")
                    if (url.startsWith("https://www.mangaupdates.com/author/"))
                        return@mapNotNull DatabaseInterface.AuthorMetadata(
                            name = it.text(),
                            url = it.attr("href")
                        )

                    if (url.startsWith("https://www.mangaupdates.com/submit.html?act=add_author")) {
                        val name = url.toUrlBuilderSafe().build().getQueryParameter("author")
                            ?: return@mapNotNull null
                        return@mapNotNull DatabaseInterface.AuthorMetadata(
                            name = name,
                            url = null
                        )
                    }
                    return@mapNotNull null
                } ?: emptyList()

            val description = entry("Description")?.let {
                it.selectFirst("[id=div_desc_more]") ?: it.selectFirst("div")
            }?.also {
                it.select("a").remove()
            }?.let { TextExtractor.get(it) } ?: ""

            val tags = entry("Categories")
                ?.select("li > a")
                ?.mapNotNull { it.text().ifBlank { null } } ?: emptyList()

            val coverImageUrl = entry("Image")
                ?.selectFirst("img[src]")?.attr("src") ?: ""

            val genres = entry("Genre")?.select("a")
                ?.dropLast(1)
                ?.mapNotNull { it.text().ifBlank { null } }
                ?.map { SearchGenre(genreName = it, id = it) } ?: emptyList()

            DatabaseInterface.BookData(
                title = doc.selectFirst(".releasestitle.tabletitle")?.text()?.removeNovelTag() ?: "",
                description = description,
                alternativeTitles = TextExtractor.get(entry("Associated Names")).split("\n\n"),
                relatedBooks = relatedBooks,
                similarRecommended = similarRecommended,
                bookType = entry("Type")?.text() ?: "",
                genres = genres,
                tags = tags,
                authors = authors,
                coverImageUrl = coverImageUrl
            ).let(::Success)
        }
    }
}