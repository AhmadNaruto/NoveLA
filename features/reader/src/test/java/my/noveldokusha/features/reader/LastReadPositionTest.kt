package my.noveldokusha.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Знаковая кодировка гранулярности в Chapter.lastReadPosition:
 * >= 0 — абзацная нумерация (legacy), < 0 — нумерация по предложениям (сплит).
 */
class LastReadPositionTest {

    @Test
    fun paragraphPositionIsStoredAsIs() {
        assertEquals(0, encodeLastReadPosition(position = 0, savedWithSplit = false))
        assertEquals(7, encodeLastReadPosition(position = 7, savedWithSplit = false))
    }

    @Test
    fun splitPositionIsStoredNegated() {
        assertEquals(-1, encodeLastReadPosition(position = 0, savedWithSplit = true))
        assertEquals(-8, encodeLastReadPosition(position = 7, savedWithSplit = true))
    }

    @Test
    fun legacyValueDecodesAsParagraphNumbering() {
        assertEquals(
            LastReadPosition(position = 7, savedWithSplit = false),
            decodeLastReadPosition(raw = 7)
        )
    }

    @Test
    fun encodeDecodeRoundTripKeepsPositionAndFlag() {
        listOf(0, 1, 7, 4242).forEach { position ->
            listOf(true, false).forEach { savedWithSplit ->
                assertEquals(
                    "position=$position savedWithSplit=$savedWithSplit",
                    LastReadPosition(position = position, savedWithSplit = savedWithSplit),
                    decodeLastReadPosition(
                        encodeLastReadPosition(position, savedWithSplit)
                    )
                )
            }
        }
    }
}
