package my.noveldokusha.sourceexplorer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShouldFetchCoverTest {

    @Test
    fun `fetch only blank covers of urls not yet handled`() {
        // Пустой cover и url ещё не обрабатывался → догружаем.
        assertTrue(shouldFetchCover("u1", coverImageUrl = "", done = emptySet()))

        // Непустой cover не трогаем вообще.
        assertFalse(shouldFetchCover("u1", coverImageUrl = "https://img/1.jpg", done = emptySet()))

        // Уже обработанный url (успех или ошибка) не запрашиваем повторно.
        assertFalse(shouldFetchCover("u1", coverImageUrl = "", done = setOf("u1")))

        // done затрагивает только свой url.
        assertFalse(shouldFetchCover("u2", coverImageUrl = "", done = setOf("u1", "u2")))
        assertTrue(shouldFetchCover("u3", coverImageUrl = "", done = setOf("u1", "u2")))
    }
}
