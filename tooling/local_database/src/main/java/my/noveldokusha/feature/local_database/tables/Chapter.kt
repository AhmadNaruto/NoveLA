package my.noveldokusha.feature.local_database.tables

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    indices = [
        Index(value = ["bookUrl"])
    ]
)
data class Chapter(
    val title: String,
    @PrimaryKey val url: String,
    val bookUrl: String,
    val position: Int,
    val read: Boolean = false,
    val lastReadPosition: Int = 0,
    val lastReadOffset: Int = 0,
    /** Дата публикации главы (unix epoch, секунды); null — неизвестна. */
    val uploaded: Long? = null,
    /** Позиция просмотра видео в мс; 0 = не смотрели (content_type = "video"). */
    val videoPositionMs: Long = 0,
    /** Длительность видео в мс; 0 = неизвестна. */
    val videoDurationMs: Long = 0,
    /** Сезон/том (поле volume из Lua-плагина); null — неизвестен. */
    val volume: String? = null,
)