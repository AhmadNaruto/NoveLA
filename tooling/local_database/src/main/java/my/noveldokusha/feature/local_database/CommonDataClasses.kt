package my.noveldokusha.feature.local_database

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.room.Embedded
import my.noveldokusha.feature.local_database.tables.Book
import my.noveldokusha.feature.local_database.tables.Chapter

@Immutable
data class BookMetadata(
    val title: String,
    val url: String,
    val coverImageUrl: String = "",
    val description: String = "",
    val rating: String = "",
    // Тип контента (NOVEL/MANGA/COMIC). Пустая строка = NOVEL.
    val contentType: String = ""
) {
    override fun equals(other: Any?): Boolean =
        if (other is BookMetadata) (url == other.url) else false

    override fun hashCode(): Int = url.hashCode()
}

@Immutable
data class ChapterMetadata(val title: String, val url: String) {
    override fun equals(other: Any?): Boolean =
        if (other is ChapterMetadata) (url == other.url) else false

    override fun hashCode(): Int = url.hashCode()
}

@Stable
data class BookWithContext(
    @Embedded val book: Book,
    val chaptersCount: Int,
    val chaptersReadCount: Int
)

@Stable
data class ChapterWithContext(
    @Embedded val chapter: Chapter,
    val downloaded: Boolean,
    val lastReadChapter: Boolean
)