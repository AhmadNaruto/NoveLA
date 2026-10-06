package my.noveldokusha.core

/**
 * Полная очистка скачанных (офлайн) видео.
 *
 * Общий контракт для модулей, которым недоступен features:reader
 * (например, features:settings — оттуда запускается очистка в настройках):
 * реализация — VideoDownloadManager, привязка — AppModule.
 */
interface OfflineVideoCleaner {
    /** Удаляет все офлайн-видео; в media3 удаление происходит асинхронно. */
    fun removeAll()
}
