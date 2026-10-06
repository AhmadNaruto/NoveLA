package my.noveldokusha.coreui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ImageViewRetryTest {

    @Test
    fun placeholderIntHidesRetry() = assertFalse(shouldShowRetry(0))

    @Test
    fun stringShowsRetry() = assertTrue(shouldShowRetry("https://example.com/cover.jpg"))

    @Test
    fun missingFileShowsRetry() = assertTrue(shouldShowRetry(File("/nonexistent/cover.jpg")))
}
