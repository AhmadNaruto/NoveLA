package my.noveldokusha.features.reader.video

/** Состояние загрузки главы — бейдж в списке глав. */
enum class ChapterDownloadUiState { NONE, QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

data class ChapterDownloadUi(
    val state: ChapterDownloadUiState,
    val progress: Float? = null, // 0f..1f; null = индетерминированный прогресс
)
