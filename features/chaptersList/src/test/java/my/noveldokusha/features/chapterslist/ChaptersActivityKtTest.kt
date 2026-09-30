package my.noveldokusha.features.chapterslist

import my.noveldokusha.feature.local_database.tables.Chapter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChaptersActivityKtTest {

    private fun chapter(url: String, position: Int, read: Boolean = false) =
        Chapter(title = url, url = url, bookUrl = "http://book", position = position, read = read)

    // (a) lastRead есть в списке и не дочитана → она же (resume позиции).
    @Test
    fun `lastRead unfinished returns that chapter`() {
        val chapters = listOf(chapter("e1", 0), chapter("e2", 1))
        assertEquals("e2", pickContinueChapter("e2", chapters)?.url)
    }

    // (b) lastRead дочитана → первый непросмотренный по позиции.
    @Test
    fun `lastRead finished falls to first unread by position`() {
        val chapters = listOf(
            chapter("e1", 0, read = true),
            chapter("e2", 1, read = true),
            chapter("e3", 2),
            chapter("e4", 3),
        )
        assertEquals("e3", pickContinueChapter("e2", chapters)?.url)
    }

    // (c) lastRead отсутствует → первый непросмотренный.
    @Test
    fun `missing lastRead picks first unread`() {
        val chapters = listOf(chapter("e1", 0, read = true), chapter("e2", 1))
        assertEquals("e2", pickContinueChapter(null, chapters)?.url)
    }

    // (d) все прочитаны → первая по позиции.
    @Test
    fun `all read picks first chapter`() {
        val chapters = listOf(chapter("e1", 0, read = true), chapter("e2", 1, read = true))
        assertEquals("e1", pickContinueChapter("e2", chapters)?.url)
    }

    // (e) устаревший lastRead (нет в списке, например после ре-синка) → как отсутствующий.
    @Test
    fun `stale lastRead treated as absent`() {
        val chapters = listOf(chapter("e1", 0), chapter("e2", 1, read = true))
        assertEquals("e1", pickContinueChapter("gone", chapters)?.url)
    }

    // Порядок берётся из position, а не из порядка списка (экран может быть desc).
    @Test
    fun `picks by position regardless of list order`() {
        // Без сортировки первым unread был бы e3 (первый в списке) — по position это e2.
        val chapters = listOf(chapter("e3", 2), chapter("e2", 1), chapter("e1", 0, read = true))
        assertEquals("e2", pickContinueChapter(null, chapters)?.url)
    }
}
