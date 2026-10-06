package my.noveldokusha.logging

import android.util.Log
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timber.Tree that appends logs to a file.
 * Only logs at or above [minPriority] are written.
 * On overflow the current file is kept as `<name>.1` (one previous copy),
 * so crash reports from the session before rotation are not lost.
 */
class FileTree(
    private val file: File,
    private val minPriority: Int = Log.WARN,
    private val maxFileSizeBytes: Long = MAX_FILE_SIZE
) : Timber.Tree() {

    init {
        file.parentFile?.mkdirs()
        rotateIfNeeded()
    }

    override fun isLoggable(tag: String?, priority: Int): Boolean = priority >= minPriority

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val level = when (priority) {
            Log.ERROR -> "E"
            Log.WARN -> "W"
            Log.INFO -> "I"
            Log.DEBUG -> "D"
            else -> "V"
        }
        synchronized(lock) {
            try {
                // Rotate if needed (check inside lock for thread safety)
                rotateIfNeeded()
                val timestamp = DATE_FORMAT.format(Date())
                // Log.getStackTraceString возвращает "" для null — стек дописываем только если он есть
                val stack = Log.getStackTraceString(t)
                val stackSuffix = if (stack.isEmpty()) "" else "\n$stack"
                val line = "$timestamp $level/${tag ?: "unknown"}: $message$stackSuffix\n"
                file.appendText(line)
            } catch (_: Exception) {
                // Swallow — logging should never crash the app
            }
        }
    }

    // Вызывается только под lock (или в init до публикации объекта)
    private fun rotateIfNeeded() {
        if (!file.exists() || file.length() <= maxFileSizeBytes) return
        val backup = File(file.parentFile, file.name + ".1")
        backup.delete()
        file.renameTo(backup)
    }

    companion object {
        private val lock = Any()
        // ponytail: SimpleDateFormat не потокобезопасен, но вызывается только внутри lock
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private const val MAX_FILE_SIZE = 5L * 1024 * 1024 // 5 MB
    }
}
