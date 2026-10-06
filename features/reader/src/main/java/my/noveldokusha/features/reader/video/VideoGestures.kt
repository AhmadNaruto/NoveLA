package my.noveldokusha.features.reader.video

/**
 * Жесты видеоплеера: чистая математика без Android-зависимостей.
 *
 * Модель (порт математики mpv-android TouchGestures, MIT): порог распознавания
 * задаёт вызывающий ([GestureConfig.dragThresholdPx]), тап/двойной тап/свайп
 * превращаются в [GestureAction], который Activity диспатчит на плеер/систему.
 */

// Длительность одиночного тапа, мс: дольше — уже drag или long-press.
const val TAP_MAX_DURATION_MS = 300L

// Окно накопления двойного/тройного тапа, мс: тап вне окна начинает серию заново.
const val DOUBLE_TAP_WINDOW_MS = 300L

// Фактор скорости при удержании; при отпускании отдаётся исходная скорость плеера.
const val LONG_PRESS_SPEED_FACTOR = 2.0f

data class GestureConfig(
    // Шаг перемотки двойным тапом, мс (превью VIDEO_SEEK_STEP_MS).
    val seekStepMs: Int,
    // Порог начала горизонтального drag'а в пикселях; пересчёт доли из
    // превью (fraction × width) делает вызывающий.
    val dragThresholdPx: Float,
    // true — левая половина экрана отвечает за громкость, правая за яркость.
    val swappedSides: Boolean,
    val doubleTapEnabled: Boolean,
    val verticalSwipeEnabled: Boolean,
    val longPressSpeedEnabled: Boolean,
) {
    // N тапов в окне → N * шаг. Знак (вперёд/назад) задаёт вызывающий:
    // левая половина экрана — перемотка назад.
    fun seekDeltaMs(tapCount: Int): Long = tapCount * seekStepMs.toLong()
}

// Действие, порожденное жестом; None — жест не распознан, событие уходит дальше.
sealed interface GestureAction {
    data class Seek(val deltaMs: Long) : GestureAction
    data class SeekTo(val ms: Long) : GestureAction
    // Абсолютная яркость экрана 0..1.
    data class Brightness(val fraction: Float) : GestureAction
    // Дельта громкости в долях максимума: -1..1 (× maxVolume делает вызывающий).
    data class VolumeDelta(val fraction: Float) : GestureAction
    data class Speed(val factor: Float) : GestureAction
    data object ToggleControls : GestureAction
    data object None : GestureAction
}

/**
 * Накопитель тапов для перемотки: тапы в окне [windowMs] наращивают серию,
 * тап после окна начинает новую. Часы инжектируются — тесты ходят по фейку.
 */
class DoubleTapAccumulator(
    private val windowMs: Long = DOUBLE_TAP_WINDOW_MS,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var lastTapMs = 0L
    private var count = 0

    val tapCount: Int get() = count

    // Возвращает длину серии после этого тапа.
    fun onTap(): Int {
        val now = nowMs()
        count = if (count > 0 && now - lastTapMs <= windowMs) count + 1 else 1
        lastTapMs = now
        return count
    }

    // Нет активной серии — накопление начнётся с нуля.
    fun isExpired(): Boolean = count == 0 || nowMs() - lastTapMs > windowMs

    fun reset() {
        count = 0
    }
}

// Тап — отпускание без движения длительностью меньше TAP_MAX_DURATION_MS.
fun isTap(durationMs: Long): Boolean = durationMs in 0L until TAP_MAX_DURATION_MS

/**
 * Цель перемотки: current + delta, кламп к [0, durationMs].
 * durationMs <= 0 (C.TIME_UNSET из media3 включительно) → null: длительность
 * неизвестна, жест перемотки выключен.
 */
fun clampSeekMs(currentMs: Long, deltaMs: Long, durationMs: Long): Long? {
    if (durationMs <= 0) return null
    return (currentMs + deltaMs).coerceIn(0L, durationMs)
}

/**
 * Горизонтальный drag: доля dx/width от длительности, кламп к [0, duration].
 * ponytail: окно перемотки = длительность (свайп во всю ширину перебирает
 * всё видео); отдельная настройка окна — если станет тесно.
 */
fun dragSeekTargetMs(
    dxPx: Float,
    screenWidthPx: Float,
    currentMs: Long,
    durationMs: Long,
): Long? {
    if (durationMs <= 0 || screenWidthPx <= 0f) return null
    val deltaMs = ((dxPx / screenWidthPx) * durationMs).toLong()
    return (currentMs + deltaMs).coerceIn(0L, durationMs)
}

// Левая половина экрана — яркость, правая — громкость; swappedSides зеркалит.
fun isBrightnessSide(startXFraction: Float, swappedSides: Boolean): Boolean =
    (startXFraction < 0.5f) != swappedSides

/**
 * Абсолютная яркость 0..1: стартовое значение + свайп вверх.
 * deltaYpx — движение пальцем по оси Y (вниз положительное), поэтому вверх
 * уменьшает deltaYpx и увеличивает яркость.
 */
fun brightnessFraction(startFraction: Float, deltaYpx: Float, screenHeightPx: Float): Float {
    if (screenHeightPx <= 0f) return startFraction.coerceIn(0f, 1f)
    return (startFraction - deltaYpx / screenHeightPx).coerceIn(0f, 1f)
}

// Дельта громкости в долях максимума (-1..1), тот же знак, что у яркости.
fun volumeFractionDelta(deltaYpx: Float, screenHeightPx: Float): Float {
    if (screenHeightPx <= 0f) return 0f
    return (-deltaYpx / screenHeightPx).coerceIn(-1f, 1f)
}

// Удержание → ускорение; отпускание → исходная скорость плеера, которую
// вызывающий запоминает до начала жеста (восстановление без состояния здесь).
fun longPressSpeed(pressed: Boolean, originalSpeed: Float): GestureAction =
    GestureAction.Speed(if (pressed) LONG_PRESS_SPEED_FACTOR else originalSpeed)
