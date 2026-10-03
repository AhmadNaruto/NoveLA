package my.noveldokusha.features.reader.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Нетривиальная математика жестов: накопление двойного тапа, drag-конверсия,
 * клампы (позиция/яркость), зеркалирование половин, восстановление скорости.
 */
class VideoGesturesTest {

    private val config = GestureConfig(
        seekStepMs = 10_000,
        dragThresholdPx = 60f,
        swappedSides = false,
        doubleTapEnabled = true,
        verticalSwipeEnabled = true,
        longPressSpeedEnabled = true,
    )

    // ── Двойной тап ────────────────────────────────────────────────────────

    @Test
    fun `taps inside window accumulate and expire after it`() {
        var now = 0L
        val acc = DoubleTapAccumulator(nowMs = { now })

        assertEquals(1, acc.onTap())
        now = 150
        assertEquals(2, acc.onTap())
        now = 280
        assertEquals(3, acc.onTap())
        assertEquals(30_000L, config.seekDeltaMs(acc.tapCount))

        // Тап после окна (280 + 300 + 1) — серия начинается заново.
        now = 280 + DOUBLE_TAP_WINDOW_MS + 1
        assertTrue(acc.isExpired())
        assertEquals(1, acc.onTap())

        acc.reset()
        assertEquals(0, acc.tapCount)
        assertTrue(acc.isExpired())
    }

    @Test
    fun `double tap seek clamps at zero and at duration`() {
        // Назад от начала — в 0.
        assertEquals(
            0L,
            clampSeekMs(currentMs = 1_000, deltaMs = -config.seekDeltaMs(2), durationMs = 60_000L)
        )
        // Вперёд за конец — в длительность.
        assertEquals(
            12_000L,
            clampSeekMs(currentMs = 5_000, deltaMs = config.seekDeltaMs(2), durationMs = 12_000L)
        )
        // Неизвестная длительность (C.TIME_UNSET = Long.MIN_VALUE + 1) — выключено.
        assertNull(
            clampSeekMs(
                currentMs = 5_000,
                deltaMs = config.seekDeltaMs(1),
                durationMs = -9_223_372_036_854_775_807L
            )
        )
    }

    // ── Горизонтальный drag ────────────────────────────────────────────────

    @Test
    fun `drag converts pixel delta to milliseconds by screen width`() {
        // 10% ширины при длительности 60с → 6с вперёд.
        assertEquals(
            6_000L,
            dragSeekTargetMs(dxPx = 100f, screenWidthPx = 1_000f, currentMs = 0L, durationMs = 60_000L)
        )
        // Назад: отрицательный dx упирается в 0.
        assertEquals(
            0L,
            dragSeekTargetMs(dxPx = -500f, screenWidthPx = 1_000f, currentMs = 3_000L, durationMs = 60_000L)
        )
        // Перебор за концом — упирается в длительность.
        assertEquals(
            60_000L,
            dragSeekTargetMs(dxPx = 900f, screenWidthPx = 1_000f, currentMs = 59_000L, durationMs = 60_000L)
        )
    }

    @Test
    fun `drag seek is disabled when duration or width unknown`() {
        assertNull(
            dragSeekTargetMs(
                dxPx = 100f,
                screenWidthPx = 1_000f,
                currentMs = 0L,
                durationMs = -9_223_372_036_854_775_807L // C.TIME_UNSET
            )
        )
        assertNull(
            dragSeekTargetMs(dxPx = 100f, screenWidthPx = 0f, currentMs = 0L, durationMs = 60_000L)
        )
    }

    // ── Яркость и половины экрана ──────────────────────────────────────────

    @Test
    fun `brightness fraction clamps to zero and one`() {
        // Свайп вверх (dy < 0) из 0.5 на полвысоты → 1.0.
        assertEquals(
            1f,
            brightnessFraction(startFraction = 0.5f, deltaYpx = -500f, screenHeightPx = 1_000f),
            0f
        )
        // Свайп вниз из 0.2 → 0.
        assertEquals(
            0f,
            brightnessFraction(startFraction = 0.2f, deltaYpx = 400f, screenHeightPx = 1_000f),
            0f
        )
        // Обычный пересчёт без клампа.
        assertEquals(
            0.7f,
            brightnessFraction(startFraction = 0.5f, deltaYpx = -200f, screenHeightPx = 1_000f),
            0.0001f
        )
    }

    @Test
    fun `swapped sides flip the half detection`() {
        // По умолчанию: левая половина — яркость, правая — громкость.
        assertTrue(isBrightnessSide(0.3f, swappedSides = false))
        assertFalse(isBrightnessSide(0.7f, swappedSides = false))
        // Перестановка сторон зеркалит детекцию.
        assertFalse(isBrightnessSide(0.3f, swappedSides = true))
        assertTrue(isBrightnessSide(0.7f, swappedSides = true))
    }

    // ── Скорость при удержании ─────────────────────────────────────────────

    @Test
    fun `long press speeds up and restores the original speed on release`() {
        assertEquals(
            GestureAction.Speed(2.0f),
            longPressSpeed(pressed = true, originalSpeed = 1.0f)
        )
        assertEquals(
            GestureAction.Speed(1.5f),
            longPressSpeed(pressed = false, originalSpeed = 1.5f)
        )
    }
}
