package my.noveldokusha.features.reader.video

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import my.noveldokusha.reader.R
import kotlin.math.min
import kotlin.math.roundToInt

// Скорости и шаги — те же значения, что в popup скоростей меди3
// (его собственная шестерёнка закрыта; список живёт здесь).
private val playbackSpeedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val seekStepOptions = listOf(10_000, 15_000, 30_000, 60_000)
private val autoHideOptions = listOf(5_000, 10_000, 15_000, 0)

// Сетка слайдера порога drag-а: 0.02…0.20 с шагом 0.01 (17 внутренних точек).
private const val DRAG_THRESHOLD_MIN = 0.02f
private const val DRAG_THRESHOLD_MAX = 0.20f
private const val DRAG_THRESHOLD_STEPS = 17

/**
 * Состояние панели настроек видеоплеера.
 *
 * Значения — «как есть» (мс, секунды, доли ширины): панель только показывает
 * их, персистом владеет вызывающий. Дефолты совпадают с AppPreferences.
 */
data class VideoSettingsPanelState(
    val playbackSpeed: Float = 1f,
    val seekStepMs: Int = 10_000,
    val controllerAutoHideMs: Int = 5_000,
    /** Текущий вариант потока (качество/озвучка), например «720p · AniLiberty». */
    val variantLabel: String = "",
    /** Текущее HLS-разрешение; null — строка «Разрешение» не показывается. */
    val resolutionLabel: String? = null,
    val doubleTapSeek: Boolean = true,
    val verticalSwipe: Boolean = true,
    val longPressSpeed: Boolean = true,
    val swapScreenHalves: Boolean = false,
    /** Порог начала горизонтального drag-а, доля от ширины экрана. */
    val dragThreshold: Float = 0.06f,
    /** Имя выбранной аудиодорожки (имя формирует вызывающий). */
    val audioTrackLabel: String = "",
    /** Подпись субтитровой дорожки: имя, «Off» или «No subtitles». */
    val subtitlesLabel: String = "",
    /** false — строка субтитров погашена (дорожек нет). */
    val subtitlesEnabled: Boolean = true,
    val keepScreenOn: Boolean = false,
)

/**
 * Колбэки панели. Каждое изменение сразу отдаётся наружу — Activity пишет
 * его в AppPreferences сама; панель не знает о хранилище ничего.
 */
data class VideoSettingsPanelActions(
    val onPlaybackSpeedChange: (Float) -> Unit,
    val onSeekStepChange: (Int) -> Unit,
    val onControllerAutoHideChange: (Int) -> Unit,
    val onVariantClick: () -> Unit,
    val onResolutionClick: () -> Unit,
    val onDoubleTapSeekChange: (Boolean) -> Unit,
    val onVerticalSwipeChange: (Boolean) -> Unit,
    val onLongPressSpeedChange: (Boolean) -> Unit,
    val onSwapScreenHalvesChange: (Boolean) -> Unit,
    val onDragThresholdChange: (Float) -> Unit,
    val onAudioTrackClick: () -> Unit,
    val onSubtitlesClick: () -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onClose: () -> Unit,
)

/**
 * Панель настроек видеоплеера — замена AlertDialog-списку.
 *
 * Портрет: нижний лист с ручкой (максимум ~70% высоты), ландшафт: боковая
 * панель, въезжающая справа (как в MX Player); ориентация читается внутри.
 * Содержимое сгруппировано по секциям: воспроизведение, видео, жесты, звук,
 * субтитры, экран. Логических переключателей нет — только `Switch`.
 *
 * Композируйте безусловно (не оборачивайте в `if`): скрытие идёт через
 * [visible], иначе exit-анимация не отработает. Тап по затемнению закрывает
 * панель через [VideoSettingsPanelActions.onClose].
 */
@Composable
fun VideoSettingsPanel(
    visible: Boolean,
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    // Один темп у затемнения и сдвига — слои исчезают одновременно.
    val fadeSpec = tween<Float>(durationMillis = 260)
    val slideSpec = tween<IntOffset>(durationMillis = 260)

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(fadeSpec),
        exit = fadeOut(fadeSpec),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = if (isPortrait) Alignment.BottomCenter else Alignment.CenterEnd,
        ) {
            // Затемнение: тап мимо панели закрывает её.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = actions.onClose,
                    )
            )

            val shape = if (isPortrait) {
                RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            } else {
                // Справа: скругляем только левый (внутренний) край.
                RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            }
            // Лист упирается в 70% высоты, боковая панель — в 45% ширины (не больше 400dp).
            val panelSize = if (isPortrait) {
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = (configuration.screenHeightDp * 0.7f).dp)
            } else {
                val maxPanelWidth = min(400, (configuration.screenWidthDp * 0.45f).toInt())
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = maxPanelWidth.dp)
            }
            val enter = if (isPortrait) slideInVertically(slideSpec) { it }
            else slideInHorizontally(slideSpec) { it }
            val exit = if (isPortrait) slideOutVertically(slideSpec) { it }
            else slideOutHorizontally(slideSpec) { it }

            Surface(
                modifier = panelSize.animateEnterExit(enter = enter, exit = exit),
                shape = shape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 12.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isPortrait) Modifier.navigationBarsPadding()
                            else Modifier.statusBarsPadding().navigationBarsPadding()
                        )
                        // Пустые зоны листа гасят тап, чтобы он не провалился к затемнению.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    PanelHeader(isPortrait = isPortrait, onClose = actions.onClose)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    ) {
                        PlaybackSection(state = state, actions = actions)
                        VideoSection(state = state, actions = actions)
                        GesturesSection(state = state, actions = actions)
                        AudioSection(state = state, actions = actions)
                        SubtitlesSection(state = state, actions = actions)
                        ScreenSection(state = state, actions = actions)
                    }
                }
            }
        }
    }
}

/** Шапка: ручка + заголовок и кнопка закрытия в портрете, «назад» в ландшафте. */
@Composable
private fun PanelHeader(isPortrait: Boolean, onClose: () -> Unit) {
    if (isPortrait) {
        Column {
            // Ручка перетаскивания — визуальный сигнал «лист» (как в bottom sheet).
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .background(
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(2.dp),
                    )
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.video_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.video_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

// ── Секции ────────────────────────────────────────────────────────────────────

/** Воспроизведение: скорость, шаг перемотки, автоскрытие панели (чипы). */
@Composable
private fun PlaybackSection(
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
) {
    SectionHeader(stringResource(R.string.video_section_playback))

    ChipGroupLabel(stringResource(R.string.video_playback_speed))
    ChipRow {
        playbackSpeedOptions.forEach { speed ->
            FilterChip(
                selected = speed == state.playbackSpeed,
                onClick = { actions.onPlaybackSpeedChange(speed) },
                label = { Text(speedLabel(speed)) },
            )
        }
    }

    ChipGroupLabel(stringResource(R.string.video_seek_step))
    ChipRow {
        seekStepOptions.forEach { stepMs ->
            FilterChip(
                selected = stepMs == state.seekStepMs,
                onClick = { actions.onSeekStepChange(stepMs) },
                label = { Text(stringResource(R.string.video_seconds_value, stepMs / 1000)) },
            )
        }
    }

    ChipGroupLabel(stringResource(R.string.video_controller_auto_hide))
    ChipRow {
        autoHideOptions.forEach { hideMs ->
            FilterChip(
                selected = hideMs == state.controllerAutoHideMs,
                onClick = { actions.onControllerAutoHideChange(hideMs) },
                label = {
                    Text(
                        text = if (hideMs == 0) stringResource(R.string.video_auto_hide_never)
                        else stringResource(R.string.video_seconds_value, hideMs / 1000)
                    )
                },
            )
        }
    }
}

/** Видео: варианты потока и HLS-разрешение — строки, открывающие диалоги. */
@Composable
private fun VideoSection(
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
) {
    SectionHeader(stringResource(R.string.video_section_video))
    NavigationRow(
        label = stringResource(R.string.video_variant),
        value = state.variantLabel,
        onClick = actions.onVariantClick,
    )
    // Одно разрешение выбирать не из чего — строка прячется, а не гасится.
    val resolutionLabel = state.resolutionLabel
    if (resolutionLabel != null) NavigationRow(
        label = stringResource(R.string.video_resolution),
        value = resolutionLabel,
        onClick = actions.onResolutionClick,
    )
}

/** Жесты: тумблеры + порог срабатывания горизонтального drag-а. */
@Composable
private fun GesturesSection(
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
) {
    SectionHeader(stringResource(R.string.video_section_gestures))
    SwitchRow(
        label = stringResource(R.string.video_gesture_double_tap),
        checked = state.doubleTapSeek,
        onCheckedChange = actions.onDoubleTapSeekChange,
    )
    SwitchRow(
        label = stringResource(R.string.video_gesture_vertical_swipe),
        checked = state.verticalSwipe,
        onCheckedChange = actions.onVerticalSwipeChange,
    )
    SwitchRow(
        label = stringResource(R.string.video_gesture_long_press),
        checked = state.longPressSpeed,
        onCheckedChange = actions.onLongPressSpeedChange,
    )
    SwitchRow(
        label = stringResource(R.string.video_gesture_swap_sides),
        checked = state.swapScreenHalves,
        onCheckedChange = actions.onSwapScreenHalvesChange,
    )
    Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.video_gesture_sensitivity),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(state.dragThreshold * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Slider(
            value = state.dragThreshold,
            // Округление до сотых: в pref ложится чистое значение 0.06, а не 0.059999.
            onValueChange = { actions.onDragThresholdChange((it * 100).roundToInt() / 100f) },
            valueRange = DRAG_THRESHOLD_MIN..DRAG_THRESHOLD_MAX,
            steps = DRAG_THRESHOLD_STEPS,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}

/** Звук: выбор аудиодорожки. */
@Composable
private fun AudioSection(
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
) {
    SectionHeader(stringResource(R.string.video_section_audio))
    NavigationRow(
        label = stringResource(R.string.video_audio_track),
        value = state.audioTrackLabel,
        onClick = actions.onAudioTrackClick,
    )
}

/** Субтитры: выбор дорожки; без дорожек строка погашена. */
@Composable
private fun SubtitlesSection(
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
) {
    SectionHeader(stringResource(R.string.video_section_subtitles))
    NavigationRow(
        label = stringResource(R.string.video_subtitle_track),
        value = state.subtitlesLabel,
        enabled = state.subtitlesEnabled,
        onClick = actions.onSubtitlesClick,
    )
}

/** Экран: не выключать экран. */
@Composable
private fun ScreenSection(
    state: VideoSettingsPanelState,
    actions: VideoSettingsPanelActions,
) {
    SectionHeader(stringResource(R.string.video_section_screen))
    SwitchRow(
        label = stringResource(R.string.video_keep_screen_on),
        checked = state.keepScreenOn,
        onCheckedChange = actions.onKeepScreenOnChange,
    )
}

// ── Элементы ──────────────────────────────────────────────────────────────────

/**
 * Заголовок секции: мелкий titleSmall в цвете акцента —
 * как VariantSection в диалоге вариантов.
 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 18.dp, end = 4.dp, bottom = 4.dp),
    )
}

/** Подпись над группой чипов. */
@Composable
private fun ChipGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

/** Ряд чипов с переносом: семь скоростей в одну строку не влезают. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable RowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/**
 * Строка-навигация: подпись слева, текущее значение серым по правому краю
 * и шеврон — тап открывает диалог выбора. Недоступная строка гасится.
 */
@Composable
private fun NavigationRow(
    label: String,
    value: String?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            modifier = Modifier.weight(1f),
        )
        if (value != null) Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
            modifier = Modifier
                .widthIn(max = 180.dp)
                .padding(end = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
        )
    }
}

/** Строка-переключатель: подпись слева, `Switch` по правому краю. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Подпись скорости: целая → «2x», дробная → «0.75x». */
private fun speedLabel(speed: Float): String {
    val base = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
    return "${base}x"
}
