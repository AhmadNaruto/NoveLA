package my.noveldokusha.core

/**
 * Единая точка конфигурации кэшей приложения.
 *
 * Все размеры, TTL, лимиты и политики кэширования живут здесь, а не разбросаны
 * по модулям: так настройки не дублируются, а границы кэшей не пересекаются
 * (каждый кэш — своя директория в cacheDir и свой бюджет).
 */
object AppCacheConfig {

    // Coil: изображения (обложки, иконки источников).
    const val IMAGE_DISK_CACHE_DIR = "image_cache"
    const val IMAGE_DISK_CACHE_BYTES = 100L * 1024 * 1024 // 100 MB
    const val IMAGE_MEMORY_CACHE_PERCENT = 0.25
    const val IMAGE_CROSSFADE_MS = 300

    // OkHttp: HTTP-ответы скрапера.
    const val NETWORK_CACHE_DIR = "network_cache"
    const val NETWORK_CACHE_BYTES = 50L * 1024 * 1024 // 50 MB

    // Свежесть HTTP-ответа в network_cache (Cache-Control max-age).
    const val HTTP_MAX_AGE_SECONDS = 600 // 10 минут, okhttp CacheControl.maxAge ждёт Int

    // Страницы манги.
    const val PAGE_IMAGES_CACHE_DIR = "page_images"
    const val PAGE_IMAGES_CACHE_BYTES = 256L * 1024 * 1024 // 256 MB

    // DNS over HTTPS.
    const val DNS_CACHE_TTL_MS = 300_000L // 5 минут
    const val DNS_NEGATIVE_TTL_MS = 60_000L // пауза после полного фейла резолва
    const val DNS_NEGATIVE_MAX_SIZE = 512

    // Lua-плагины.
    const val LUA_HTTP_GET_CACHE_TTL_MS = 2_000L
    const val LUA_HTTP_GET_CACHE_MAX_ENTRIES = 100
    const val LUA_HTTP_GET_CACHE_MAX_BODY_BYTES = 4_000_000
    const val LUA_MAX_CACHED_SOURCES = 30

    // ── Политика: что кэшируем, а что нет ──

    /**
     * Статусы HTTP, пригодные для кэша: из кэша читаем только такие ответы
     * и в кэш пишем только такие. Ошибки (4xx/5xx) не кэшируем и не читаем
     * из кэша — иначе «отравленная» 404-запись переживает ретраи
     * (см. RevalidatingCacheStrategy в App.kt).
     */
    fun isCacheableStatus(code: Int): Boolean = code in 200..299

    // Ретраи картинок (ImageView) при ошибке загрузки: максимум попыток и пауза.
    const val IMAGE_RETRY_ATTEMPTS = 2
    const val IMAGE_RETRY_DELAY_MS = 1000L

    // RGB_565: 2 байта/пиксель вместо 4 — вдвое больше обложек в кэше,
    // но без альфы. hardware-bitmap быстрее рисуется, но его нельзя
    // скопировать в software-память.
    const val IMAGE_RGB565 = true
    const val IMAGE_HARDWARE_BITMAPS = true

    // Lua http_get: кэшировать ли бинарные ответы (обычно большие и одноразовые).
    const val LUA_CACHE_BINARY = false

    // forceNetwork (каталоги источников) всегда обходит кэш — каталог должен быть свежим.
    const val LUA_FORCE_NETWORK_BYPASS = true
}
