package my.noveldokusha.features.reader.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Классификация подписей вариантов: разрешение → секция «Качество»,
 * остальное (озвучка, пустая подпись) → секция «Озвучка».
 */
class VideoVariantLabelTest {

    @Test
    fun `resolution-like labels belong to quality section`() {
        listOf("1080p", "720p", "480p", " 720P ", "sd", "hd", "fhd", "uhd", "4k").forEach {
            assertTrue(it, isResolutionLabel(it))
        }
    }

    @Test
    fun `voiceover and unknown labels do not belong to quality section`() {
        listOf("AniLiberty (Дубляж)", "Амедиа (Дубляж)", "", "1080", "720p_full", "sub").forEach {
            assertFalse(it, isResolutionLabel(it))
        }
    }
}
