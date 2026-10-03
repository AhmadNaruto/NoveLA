package my.noveldokusha.features.reader.video

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.abs
import kotlin.math.roundToInt

/** Индикатор жеста для оверлея Activity: живёт только на время жеста. */
sealed interface GestureHud {
    data class Seek(val positionMs: Long, val durationMs: Long) : GestureHud
    data class Brightness(val fraction: Float) : GestureHud
    data class Volume(val fraction: Float) : GestureHud
    data class Speed(val factor: Float) : GestureHud
}

private const val LONG_PRESS_DELAY_MS = 500L
private const val HUD_HIDE_DELAY_MS = 900L

// Трети экрана: центральная — тап (показать/спрятать панель), края —
// перемотка двойным тапом. Как у VLC/MX.
private const val CENTER_ZONE_START = 0.35f
private const val CENTER_ZONE_END = 0.65f

private enum class GestureMode { NONE, SEEK, BRIGHTNESS, VOLUME, SPEED }

/**
 * Распознаватель жестов поверх [PlayerView].
 *
 * Почему не Modifier.pointerInput поверх AndroidView: официальный
 * PointerInteropFilter.android.kt гасит события View, если Compose их
 * сконсьюмил (и наоборот) — два слоя жестов в одном event stream
 * взаимно исключают друг друга. Поэтому жесты живут внутри View:
 * слушатель потребляет события, кроме зон исключения, куда течёт
 * штатный тач контролов меди3.
 *
 * Перемотка при drag'е — только предпросмотр в HUD, коммит на ACTION_UP:
 * частые player.seekTo() во время движения дают лаги (androidx/media #3403).
 * Яркость и громкость применяются сразу — они дешёвые.
 */
internal class PlayerGestureDispatcher(
    private val activity: Activity,
    private val player: Player,
    // Порог хранится долей от ширины экрана, пиксели считаем здесь: ширина
    // вью известна только в момент тача (до layout = 0).
    private val configProvider: (viewWidthPx: Float) -> GestureConfig,
    // Верхняя полоса (статус-бар + градиентная шапка) и низ (контролы меди3):
    // там жест не начинается, чтобы не спорить с штатными тачами.
    private val exclusionTopPx: () -> Int,
    private val exclusionBottomPx: () -> Int,
    private val onToggleControls: () -> Unit,
    private val onHud: (GestureHud?) -> Unit,
) : View.OnTouchListener {

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager =
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tapAccumulator = DoubleTapAccumulator()

    private var handled = false
    private var mode = GestureMode.NONE

    private var downTimeMs = 0L
    private var downX = 0f
    private var downY = 0f
    // Указатель, начинавший жест: при поднятии основного пальца системе
    // перенумеровывают указатели и event.x/y прыгают на координаты другого.
    private var pointerId = 0
    private var startBrightness = 0.5f
    private var startVolumeStream = 0
    private var originalSpeed = 1f
    private var seekBaseMs = 0L
    private var seekTargetMs = 0L

    private var pendingSingleTap: Runnable? = null
    private var pendingLongPress: Runnable? = null
    private var pendingHudHide: Runnable? = null

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handled = !inExclusion(view, event)
                if (!handled) return false
                onDown(view, event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!handled) return false
                onMove(view, event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!handled) return false
                onUp(view, event)
                handled = false
                return true
            }
            // Отмена (перехват системой): потребляем её же — раз DOWN был наш.
            MotionEvent.ACTION_CANCEL -> {
                if (handled) cancelAll()
                handled = false
                return true
            }
            else -> return handled
        }
    }

    // Зона исключения считается по нажатию: внутри неё событие не наше,
    // оно уходит контролу меди3 целиком, включая последующие MOVE/UP.
    private fun inExclusion(view: View, event: MotionEvent): Boolean =
        event.y < exclusionTopPx() || event.y > view.height - exclusionBottomPx()

    private fun onDown(view: View, event: MotionEvent) {
        downTimeMs = event.eventTime
        pointerId = event.getPointerId(event.actionIndex)
        downX = event.x
        downY = event.y
        mode = GestureMode.NONE
        originalSpeed = player.playbackParameters.speed
        seekBaseMs = player.currentPosition
        seekTargetMs = seekBaseMs
        startBrightness = currentScreenBrightness()
        startVolumeStream = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Серию тапов здесь не сбрасываем: окно DOUBLE_TAP_WINDOW_MS контролирует
        // сам аккумулятор, а прерывание drag'ом рвёт серию в enter().
        cancelPendingTaps()

        if (configProvider(view.width.toFloat()).longPressSpeedEnabled) {
            pendingLongPress = Runnable {
                if (mode == GestureMode.NONE) {
                    mode = GestureMode.SPEED
                    applyAction(GestureAction.Speed(LONG_PRESS_SPEED_FACTOR))
                }
            }.also { handler.postDelayed(it, LONG_PRESS_DELAY_MS) }
        }
    }

    private fun onMove(view: View, event: MotionEvent) {
        // Координаты берём по указателю, начинавшему жест: палец-основание
        // могли поднять при втором пальце на экране — указатели перенумеруют,
        // и event.x/y (указатель 0) прыгнули бы на координаты другого пальца.
        val pointerIndex = event.findPointerIndex(pointerId)
        if (pointerIndex < 0) return
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val config = configProvider(view.width.toFloat())
        val dx = x - downX
        val dy = y - downY
        val threshold = config.dragThresholdPx

        if (mode == GestureMode.NONE) {
            // Побеждает ось с большим убеганием: так горизонтальный drag не
            // «съедает» вертикальный свайп на диагонале, и наоборот.
            val horizontal = abs(dx) > threshold && abs(dx) > abs(dy)
            val vertical = abs(dy) > threshold && abs(dy) > abs(dx)
            when {
                horizontal -> enter(GestureMode.SEEK)
                vertical && config.verticalSwipeEnabled -> {
                    enter(if (isBrightnessSide(
                            startXFraction = downX / view.width,
                            swappedSides = config.swappedSides,
                        )
                    ) GestureMode.BRIGHTNESS else GestureMode.VOLUME)
                }
            }
        }

        when (mode) {
            GestureMode.SEEK -> {
                dragSeekTargetMs(
                    dxPx = dx,
                    screenWidthPx = view.width.toFloat(),
                    currentMs = seekBaseMs,
                    durationMs = player.duration,
                )?.let { target ->
                    seekTargetMs = target
                    // Только HUD: коммит на ACTION_UP (см. док класса).
                    showHud(GestureHud.Seek(target, player.duration))
                }
            }
            // Яркость и громкость применяются сразу — они дешёвые, и отклик
            // на каждое движение пальца и есть смысл этих жестов.
            GestureMode.BRIGHTNESS -> applyAction(GestureAction.Brightness(
                brightnessFraction(
                    startFraction = startBrightness,
                    deltaYpx = y - downY,
                    screenHeightPx = view.height.toFloat(),
                )
            ))
            GestureMode.VOLUME -> applyAction(GestureAction.VolumeDelta(
                volumeFractionDelta(
                    deltaYpx = y - downY,
                    screenHeightPx = view.height.toFloat(),
                )
            ))
            else -> Unit
        }
    }

    private fun enter(newMode: GestureMode) {
        mode = newMode
        // Началось движение — серия тапов прервана: тап в пределах окна после
        // drag'а не должен считаться вторым. Отложенный тап гасим, иначе он
        // покажет панель посреди drag'а.
        tapAccumulator.reset()
        cancelPendingTaps()
        cancelPendingLongPress()
    }

    private fun onUp(view: View, event: MotionEvent) {
        cancelPendingLongPress()
        val config = configProvider(view.width.toFloat())
        val previousMode = mode
        mode = GestureMode.NONE
        // Та же логика координат, что в onMove; UP приходит последним, но в
        // мультитач указатель уже мог быть перенумерован — fallback на 0.
        val pointerIndex = event.findPointerIndex(pointerId)
        val x = if (pointerIndex >= 0) event.getX(pointerIndex) else event.x
        val y = if (pointerIndex >= 0) event.getY(pointerIndex) else event.y

        when (previousMode) {
            GestureMode.SPEED ->
                applyAction(GestureAction.Speed(originalSpeed))
            // Коммит только по отпусканию — во время движения был один HUD.
            GestureMode.SEEK -> applyAction(GestureAction.SeekTo(seekTargetMs))
            GestureMode.BRIGHTNESS -> showHud(GestureHud.Brightness(
                brightnessFraction(startBrightness, y - downY, view.height.toFloat())
            ))
            GestureMode.VOLUME -> showHud(GestureHud.Volume(
                startVolumeFraction(volumeFractionDelta(y - downY, view.height.toFloat()))
            ))
            GestureMode.NONE -> onTap(event, x, view, config)
        }
    }

    /** Одиночный тап: центр — сразу показать панель, края — серия под двойной. */
    private fun onTap(event: MotionEvent, x: Float, view: View, config: GestureConfig) {
        if (!isTap(event.eventTime - downTimeMs)) return
        if (!config.doubleTapEnabled) {
            onToggleControls()
            return
        }

        val xFraction = x / view.width
        if (xFraction in CENTER_ZONE_START..CENTER_ZONE_END) {
            // Тап по центру — без задержки: ожидание окна двойного тапа
            // на показе панели ощущается как подвисание.
            tapAccumulator.reset()
            onToggleControls()
            return
        }

        if (tapAccumulator.onTap() == 1) {
            // Первый тап у края: ждём второй. Не пришёл — обычный тап,
            // показываем панель (окно DOUBLE_TAP_WINDOW_MS).
            pendingSingleTap = Runnable {
                tapAccumulator.reset()
                onToggleControls()
            }.also { handler.postDelayed(it, DOUBLE_TAP_WINDOW_MS) }
        } else {
            // Второй и далее тапы в окне → накопленная перемотка.
            pendingSingleTap?.let(handler::removeCallbacks)
            pendingSingleTap = null
            val stepMs = config.seekStepMs.toLong() * if (xFraction < 0.5f) -1 else 1
            applyAction(GestureAction.Seek(stepMs))
        }
    }

    /**
     * Единственное место, где действие доходит до плеера/системы и HUD:
     * жесты выше только вычисляют [GestureAction].
     */
    private fun applyAction(action: GestureAction) {
        when (action) {
            is GestureAction.Seek ->
                clampSeekMs(player.currentPosition, action.deltaMs, player.duration)
                    ?.let { target ->
                        player.seekTo(target)
                        showHud(GestureHud.Seek(target, player.duration))
                    }
            is GestureAction.SeekTo ->
                clampSeekMs(seekBaseMs, action.ms - seekBaseMs, player.duration)
                    ?.let { target ->
                        player.seekTo(target)
                        showHud(GestureHud.Seek(target, player.duration))
                    }
            is GestureAction.Brightness -> {
                activity.window.attributes = activity.window.attributes
                    .apply { screenBrightness = action.fraction }
                showHud(GestureHud.Brightness(action.fraction))
            }
            is GestureAction.VolumeDelta -> {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val target = (startVolumeStream + action.fraction * max)
                    .roundToInt()
                    .coerceIn(0, max)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                showHud(GestureHud.Volume(target / max.toFloat()))
            }
            is GestureAction.Speed -> {
                player.playbackParameters = PlaybackParameters(action.factor)
                showHud(GestureHud.Speed(action.factor))
            }
            GestureAction.ToggleControls -> onToggleControls()
            GestureAction.None -> Unit
        }
    }

    // Дельта свайпа → абсолютная доля громкости относительно старта жеста.
    private fun startVolumeFraction(delta: Float): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max == 0) return 0f
        return ((startVolumeStream + delta * max) / max).coerceIn(0f, 1f)
    }

    private fun showHud(hud: GestureHud?) {
        pendingHudHide?.let(handler::removeCallbacks)
        pendingHudHide = null
        onHud(hud)
        if (hud != null) {
            pendingHudHide = Runnable { onHud(null) }
                .also { handler.postDelayed(it, HUD_HIDE_DELAY_MS) }
        }
    }

    private fun currentScreenBrightness(): Float {
        val fromWindow = activity.window.attributes.screenBrightness
        if (fromWindow >= 0f) return fromWindow.coerceIn(0f, 1f)
        // Яркость не установлена (системный режим) — читаем системное значение:
        // SCREEN_BRIGHTNESS доступен на чтение без разрешений.
        return runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128,
            ) / 255f
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    private fun cancelPendingTaps() {
        pendingSingleTap?.let(handler::removeCallbacks)
        pendingSingleTap = null
        pendingHudHide?.let(handler::removeCallbacks)
        pendingHudHide = null
        onHud(null)
    }

    private fun cancelPendingLongPress() {
        pendingLongPress?.let(handler::removeCallbacks)
        pendingLongPress = null
    }

    private fun cancelAll() {
        cancelPendingTaps()
        cancelPendingLongPress()
        if (mode == GestureMode.SPEED) {
            player.playbackParameters = PlaybackParameters(originalSpeed)
        }
        mode = GestureMode.NONE
    }
}

/**
 * Показ/скрытие контролов вручную: наш слой возвращает true из onTouch, поэтому
 * штатный [PlayerView.performClick] не вызывается и сам панель не покажет.
 */
internal fun PlayerView.toggleController() {
    if (isControllerShown()) hideController() else showController()
}
