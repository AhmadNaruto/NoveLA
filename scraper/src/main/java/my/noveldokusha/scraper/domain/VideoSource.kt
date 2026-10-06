package my.noveldokusha.scraper.domain

/**
 * Один видеопоток эпизода, возвращаемый Lua-плагином из getVideoList.
 * headers — заголовки запроса к потоку и его сегментам (Referer, UA и т.п.).
 * mime — необязательный хинт типа контента из поля `mime` таблицы плагина
 * (нормализуется в LuaSourceAdapter); null → плеер определяет тип по URL.
 */
data class VideoSource(
    val url: String,
    val quality: String = "",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<VideoSubtitle> = emptyList(),
    val mime: String? = null,
)

data class VideoSubtitle(
    val url: String,
    val label: String,
    val lang: String = "",
)
