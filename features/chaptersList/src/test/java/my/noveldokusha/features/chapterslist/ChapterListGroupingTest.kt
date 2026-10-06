package my.noveldokusha.features.chapterslist

import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterListGroupingTest {

    private fun chapter(url: String, position: Int, volume: String? = null) = ChapterWithContext(
        chapter = Chapter(
            title = url,
            url = url,
            bookUrl = "bookUrl",
            position = position,
            volume = volume
        ),
        downloaded = false,
        lastReadChapter = false
    )

    private fun chapterUrls(items: List<ChapterListItem>) = items
        .filterIsInstance<ChapterListItem.Chapter>()
        .map { it.data.chapter.url }

    private fun headerVolumes(items: List<ChapterListItem>) = items
        .filterIsInstance<ChapterListItem.VolumeHeader>()
        .map { it.volume }

    // ─── buildChapterListItems ───────────────────────────────────────────────

    @Test
    fun `chapters without volume produce no headers and keep order`() {
        val chapters = listOf(chapter("a", 0), chapter("b", 1), chapter("c", 2))

        val items = buildChapterListItems(chapters)

        assertTrue(headerVolumes(items).isEmpty())
        assertEquals(listOf("a", "b", "c"), chapterUrls(items))
    }

    @Test
    fun `same volume in a row gets one header before the group`() {
        val chapters = listOf(
            chapter("a", 0, "Сезон 1"),
            chapter("b", 1, "Сезон 1"),
            chapter("c", 2, "Сезон 2"),
        )

        val items = buildChapterListItems(chapters)

        assertEquals(listOf("Сезон 1", "Сезон 2"), headerVolumes(items))
        assertEquals(listOf("a", "b", "c"), chapterUrls(items))
        // Заголовок стоит строго перед первой главой группы.
        assertEquals(
            listOf(
                ChapterListItem.VolumeHeader("Сезон 1", "vol_Сезон 1_1"),
                ChapterListItem.Chapter(chapters[0]),
                ChapterListItem.Chapter(chapters[1]),
                ChapterListItem.VolumeHeader("Сезон 2", "vol_Сезон 2_1"),
                ChapterListItem.Chapter(chapters[2]),
            ),
            items
        )
    }

    @Test
    fun `null and blank volume produce no header but split groups`() {
        val chapters = listOf(
            chapter("a", 0, "1"),
            chapter("b", 1, null),
            chapter("c", 2, ""),
            chapter("d", 3, "1"),
        )

        val items = buildChapterListItems(chapters)

        // Пустая строка volume не даёт пустого заголовка; группа "1" начинается заново.
        assertEquals(listOf("1", "1"), headerVolumes(items))
        assertEquals(listOf("a", "b", "c", "d"), chapterUrls(items))
    }

    @Test
    fun `repeated volume after a gap gets its own header with unique keys`() {
        val chapters = listOf(
            chapter("a", 0, "1"),
            chapter("b", 1, "2"),
            chapter("c", 2, "1"),
        )

        val items = buildChapterListItems(chapters)

        val headers = items.filterIsInstance<ChapterListItem.VolumeHeader>()
        assertEquals(3, headers.size)
        assertEquals(headers.size, headers.map { it.key }.toSet().size)
    }

    @Test
    fun `empty chapter list gives empty items`() {
        assertTrue(buildChapterListItems(emptyList()).isEmpty())
    }

    // ─── lazyIndexForChapterUrl ──────────────────────────────────────────────

    @Test
    fun `lazy index skips inserted headers and the book header`() {
        val chapters = listOf(
            chapter("a", 0, "1"),
            chapter("b", 1, "1"),
            chapter("c", 2, "2"),
        )
        val items = buildChapterListItems(chapters)

        // Позиции LazyColumn: 0 — хедер книги, 1 — "1", 2 — a, 3 — b, 4 — "2", 5 — c.
        assertEquals(2, lazyIndexForChapterUrl(items, "a"))
        assertEquals(3, lazyIndexForChapterUrl(items, "b"))
        assertEquals(5, lazyIndexForChapterUrl(items, "c"))
    }

    @Test
    fun `lazy index without volumes matches plain chapter offset`() {
        val items = buildChapterListItems(listOf(chapter("a", 0), chapter("b", 1)))

        assertEquals(1, lazyIndexForChapterUrl(items, "a"))
        assertEquals(2, lazyIndexForChapterUrl(items, "b"))
        assertEquals(-1, lazyIndexForChapterUrl(items, "missing"))
    }
}
