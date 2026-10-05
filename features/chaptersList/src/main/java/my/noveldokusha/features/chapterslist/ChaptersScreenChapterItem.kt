package my.noveldokusha.features.chapterslist

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.components.SlimListItem
import my.noveldokusha.coreui.theme.InternalTheme
import my.noveldokusha.coreui.theme.PreviewThemes
import my.noveldokusha.chapterslist.R
import my.noveldokusha.feature.local_database.ChapterWithContext
import my.noveldokusha.feature.local_database.tables.Chapter
import my.noveldokusha.features.reader.video.ChapterDownloadUi
import my.noveldokusha.features.reader.video.ChapterDownloadUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalAnimationApi::class)
@Composable
internal fun ChaptersScreenChapterItem(
    chapterWithContext: ChapterWithContext,
    translatedTitle: String? = null,
    chapterSize: ChapterSize? = null,
    downloadUi: ChapterDownloadUi? = null,
    selected: Boolean,
    isLocalSource: Boolean,
    // Кнопка отмены (X) показывается только у видео-книг: у текста/манги
    // отмена снимает всю задачу книги, а не одну главу (см. onCancelChapterDownload).
    showCancelAction: Boolean,
    highlighted: Boolean = false,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val chapter = chapterWithContext.chapter

    val sizeLabel = chapterSize?.sizeBytes?.let { formatBytes(it) }

    val targetContainerColor = when {
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        highlighted -> MaterialTheme.colorScheme.secondaryContainer
        else -> Color.Transparent
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(durationMillis = 200),
        label = "chapterItemBackground"
    )

    val stableOnClick = remember(onClick) { onClick }
    val stableOnLongClick = remember(onLongClick) { onLongClick }
    val stableOnDownload = remember(onDownload) { onDownload }
    val stableOnPauseDownload = remember(onPauseDownload) { onPauseDownload }
    val stableOnResumeDownload = remember(onResumeDownload) { onResumeDownload }
    val stableOnCancelDownload = remember(onCancelDownload) { onCancelDownload }

    val badge: @Composable (() -> Unit)? = remember(chapterWithContext.lastReadChapter, chapter.read) {
        when {
            chapterWithContext.lastReadChapter -> {
                {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = stringResource(id = R.string.last_read),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            chapter.read -> {
                {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = stringResource(id = R.string.read),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            else -> null
        }
    }

    // Статус загрузки показывает только трейлинг-иконка (кольцо/глиф/цвет),
    // текстовый бейдж дублировал бы тот же сигнал в строке.

    Surface(
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.5.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(containerColor)
                .combinedClickable(
                    onClick = stableOnClick,
                    onLongClick = stableOnLongClick,
                )
        ) {
            SlimListItem(
                headlineContent = {
                    Text(
                        text = translatedTitle ?: chapter.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (chapter.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                },
                supportingContent = if (badge != null || sizeLabel != null) {
                    {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (badge != null) badge()
                            if (sizeLabel != null) {
                                Text(
                                    text = sizeLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else null,
                trailingContent = if (isLocalSource) null else {
                    {
                        // Трейлинг-состояние: локально скачанная глава или статус
                        // загрузки; NONE без скачанного — обычное «скачать».
                        val uiState = when {
                            downloadUi == null || downloadUi.state == ChapterDownloadUiState.NONE ->
                                if (chapterWithContext.downloaded) ChapterDownloadUiState.COMPLETED
                                else ChapterDownloadUiState.NONE
                            else -> downloadUi.state
                        }
                        AnimatedTransition(targetState = uiState) { state ->
                            when (state) {
                                ChapterDownloadUiState.NONE -> IconButton(onClick = stableOnDownload) {
                                    Icon(
                                        Icons.Outlined.CloudDownload,
                                        stringResource(id = R.string.download)
                                    )
                                }

                                ChapterDownloadUiState.COMPLETED -> IconButton(onClick = stableOnDownload) {
                                    Icon(
                                        Icons.Filled.CloudDownload,
                                        stringResource(id = R.string.download)
                                    )
                                }

                                // В очереди: приглушённый глиф с пульсацией — отличим
                                // и от скачанного, и от неактивного.
                                // «X» рисуем только у видео-книг (showCancelAction):
                                // у текста/манги отмена сбрасывает всю задачу книги.
                                ChapterDownloadUiState.QUEUED -> Row {
                                    if (showCancelAction) {
                                        DownloadCancelButton(onCancelDownload = stableOnCancelDownload)
                                    }
                                    IconButton(onClick = stableOnPauseDownload) {
                                        val pulse by rememberInfiniteTransition(label = "queuedPulse").animateFloat(
                                            initialValue = 0.35f,
                                            targetValue = 0.85f,
                                            animationSpec = infiniteRepeatable(tween(1000), RepeatMode.Reverse),
                                            label = "queuedPulseAlpha",
                                        )
                                        Icon(
                                            Icons.Outlined.CloudDownload,
                                            stringResource(id = R.string.download_pause),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = pulse),
                                        )
                                    }
                                }

                                ChapterDownloadUiState.DOWNLOADING -> Row {
                                    if (showCancelAction) {
                                        DownloadCancelButton(onCancelDownload = stableOnCancelDownload)
                                    }
                                    IconButton(onClick = stableOnPauseDownload) {
                                        DownloadRing(
                                            progress = downloadUi?.progress,
                                            description = stringResource(id = R.string.download_pause),
                                        )
                                    }
                                }

                                // Пауза: кольцо заморожено на прогрессе, в центре — пауза.
                                ChapterDownloadUiState.PAUSED -> Row {
                                    if (showCancelAction) {
                                        DownloadCancelButton(onCancelDownload = stableOnCancelDownload)
                                    }
                                    IconButton(onClick = stableOnResumeDownload) {
                                        val description = stringResource(id = R.string.download_resume)
                                        val colors = MaterialTheme.colorScheme
                                        Box(
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clearAndSetSemantics { contentDescription = description },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            val pausedProgress = downloadUi?.progress
                                            if (pausedProgress != null) {
                                                CircularProgressIndicator(
                                                    progress = { pausedProgress },
                                                    modifier = Modifier.size(40.dp),
                                                    color = colors.onSurfaceVariant,
                                                    trackColor = colors.surfaceVariant,
                                                )
                                            } else {
                                                // Прогресс неизвестен — тонкое статичное кольцо.
                                                Box(
                                                    Modifier
                                                        .size(40.dp)
                                                        .border(2.dp, colors.outlineVariant, CircleShape)
                                                )
                                            }
                                            Icon(
                                                Icons.Filled.Pause,
                                                null,
                                                modifier = Modifier.size(16.dp),
                                                tint = colors.onSurface,
                                            )
                                        }
                                    }
                                }

                                ChapterDownloadUiState.FAILED -> IconButton(onClick = stableOnDownload) {
                                    Icon(
                                        Icons.Outlined.CloudOff,
                                        stringResource(id = R.string.download),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}


/**
 * Отмена загрузки в строке главы (доступна в QUEUED/DOWNLOADING/PAUSED,
 * только у видео-книг — см. showCancelAction).
 * Стоит слева от статус-кольца: слот кольца всегда у правого края строки,
 * 48dp на кнопку строка отдаёт только пока глава реально качается.
 * Глиф мелкий и без фона — кольцо остаётся главным сигналом.
 */
@Composable
private fun DownloadCancelButton(onCancelDownload: () -> Unit) {
    IconButton(onClick = onCancelDownload) {
        Icon(
            Icons.Outlined.Close,
            stringResource(id = R.string.download_cancel),
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * Кольцевой прогресс загрузки главы (40dp внутри touch-таргета 48dp).
 * Долю (0..1) анимируем, без доли — индикатор вращается сам; в центре
 * процент либо глиф облака. contentDescription задаётся здесь, чтобы у
 * кнопки всегда была подпись для TalkBack.
 *
 * Пока доля слишком мала (0f — например манга без ни одной скачанной
 * страницы), дуга была бы короче штриха и кольцо выглядело бы пустым:
 * в этом диапазоне кольцо вращается, а в центре остаётся процент.
 */
@Composable
private fun DownloadRing(progress: Float?, description: String) {
    val colors = MaterialTheme.colorScheme
    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 0f,
        animationSpec = tween(300),
        label = "chapterDownloadProgress"
    )
    // Порог видимости дуги: доля, дающая длину дуги меньше штриха (4dp).
    val isSpinning = progress == null || progress < 0.03f
    Box(
        modifier = Modifier
            .size(40.dp)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (isSpinning) {
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = colors.primary,
            )
        } else {
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.size(40.dp),
                color = colors.primary,
                trackColor = colors.surfaceVariant,
            )
        }
        if (progress == null) {
            Icon(
                Icons.Outlined.CloudDownload,
                null,
                modifier = Modifier.size(16.dp),
                tint = colors.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${(animatedProgress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface,
            )
        }
    }
}


@PreviewThemes
@Composable
private fun PreviewView(
    @PreviewParameter(PreviewProvider::class) previewProviderState: PreviewProviderState
) {
    InternalTheme {
        ChaptersScreenChapterItem(
            chapterWithContext = previewProviderState.chapterWithContext,
            downloadUi = previewProviderState.downloadUi,
            selected = previewProviderState.selected,
            isLocalSource = false,
            showCancelAction = previewProviderState.showCancelAction,
            onLongClick = {},
            onClick = {},
            onDownload = {},
            onPauseDownload = {},
            onResumeDownload = {},
            onCancelDownload = {}
        )
    }
}


private data class PreviewProviderState(
    val chapterWithContext: ChapterWithContext,
    val downloadUi: ChapterDownloadUi? = null,
    val selected: Boolean,
    // false — не-видео книга: «X» отмены в трейлинге не рисуется.
    val showCancelAction: Boolean = true,
)

private class PreviewProvider : PreviewParameterProvider<PreviewProviderState> {
    override val values = sequenceOf(
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Title of the chapter",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 0,
                    read = false
                ),
                downloaded = false,
                lastReadChapter = false
            ),
            downloadUi = ChapterDownloadUi(ChapterDownloadUiState.DOWNLOADING, progress = 0.42f),
            selected = false
        ),
        // Доля 0f: кольцо вращается, отмена рядом с паузой.
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Chapter 2",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 1,
                    read = false
                ),
                downloaded = false,
                lastReadChapter = false
            ),
            downloadUi = ChapterDownloadUi(ChapterDownloadUiState.DOWNLOADING, progress = 0f),
            selected = false
        ),
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Title of the chapter, Title of the chapter, Title of the chapter, Title of the chapter, Title of the chapter,Title of the chapter ,Title of the chapter",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 0,
                    read = true
                ),
                downloaded = true,
                lastReadChapter = false
            ),
            downloadUi = ChapterDownloadUi(ChapterDownloadUiState.PAUSED, progress = 0.75f),
            selected = false,
            showCancelAction = false
        ),
        PreviewProviderState(
            chapterWithContext = ChapterWithContext(
                chapter = Chapter(
                    title = "Title of the chapter",
                    url = "url",
                    bookUrl = "bookUrl",
                    lastReadOffset = 0,
                    lastReadPosition = 0,
                    position = 0,
                    read = false
                ),
                downloaded = true,
                lastReadChapter = true
            ),
            selected = true
        )
    )
}