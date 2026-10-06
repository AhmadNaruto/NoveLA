package my.noveldokusha.features.reader.domain

internal data class InitialPositionChapter(
    val chapterIndex: Int,
    val chapterItemPosition: Int,
    val chapterItemOffset: Int,
    /** True when [chapterItemPosition] was saved while sentence splitting was enabled. */
    val savedWithSplit: Boolean = false
)