package my.noveldokusha.features.reader.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Текст «что именно качается» в FGS-уведомлении и в уведомлении 1402:
 * первые два названия через запятую, остальные — суффикс «· ещё N»
 * (локализованную строку подставляет вызывающий, здесь — заглушка).
 */
class VideoChaptersListTextTest {

    @Test
    fun `empty titles hide content text`() {
        assertNull(chaptersListText(emptyList()) { "· $it more" })
    }

    @Test
    fun `single title has no suffix`() {
        assertEquals("Episode 1", chaptersListText(listOf("Episode 1")) { "· $it more" })
    }

    @Test
    fun `two titles have no suffix`() {
        assertEquals(
            "Episode 1, Episode 2",
            chaptersListText(listOf("Episode 1", "Episode 2")) { "· $it more" },
        )
    }

    @Test
    fun `more than two titles keep first two plus suffix`() {
        val titles = listOf("Episode 1", "Episode 2", "Episode 3", "Episode 4", "Episode 5")
        assertEquals(
            "Episode 1, Episode 2 · 3 more",
            chaptersListText(titles) { "· $it more" },
        )
    }

    @Test
    fun `titles keep insertion order`() {
        assertEquals(
            "B, C · 1 more",
            chaptersListText(listOf("B", "C", "A")) { "· $it more" },
        )
    }
}
