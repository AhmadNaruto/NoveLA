package my.noveldokusha.tooling.backup_create

import timber.log.Timber
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

// Maximum size of the end-of-central-directory record: 22 bytes + 65535 comment
private const val EOCD_MAX_SIZE = 22 + 65535

/** Thrown when the finished backup archive is rejected as unusable. */
class BackupValidationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Streaming verification of a finished backup archive in a single pass:
 * entry "database.sqlite3" must exist and be non-empty (its CRC is verified
 * by ZipInputStream itself), and the end-of-central-directory record must end
 * exactly at the end of the file (otherwise the archive was truncated).
 *
 * Throws [BackupValidationException] or a [java.util.zip.ZipException] when the
 * archive is rejected and the document must be deleted. Any other exception is
 * an infrastructure failure (stream could not be read): the archive was not
 * judged in that case and the document must be kept.
 */
object BackupArchiveValidator {

    fun validate(inputStream: InputStream) {
        // Rolling tail buffer. ZipInputStream never parses the central directory,
        // so the tail is fed from every raw read through the wrapper below.
        val tail = ByteArray(EOCD_MAX_SIZE)
        val oneByte = ByteArray(1)
        var tailLength = 0
        var databaseSeen = false
        var databaseSize = 0L

        inputStream.use { stream ->
            val raw = object : FilterInputStream(stream) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = super.read(b, off, len)
                    if (read > 0) tailLength = feedTail(tail, tailLength, b, off, read)
                    return read
                }

                override fun read(): Int {
                    val byte = super.read()
                    if (byte != -1) {
                        oneByte[0] = byte.toByte()
                        tailLength = feedTail(tail, tailLength, oneByte, 0, 1)
                    }
                    return byte
                }
            }
            try {
                ZipInputStream(raw).use { zip ->
                    while (true) {
                        val entry = zip.getNextEntry() ?: break
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read == -1) break
                            entryBytes += read
                        }
                        if (entry.name == "database.sqlite3") {
                            databaseSeen = true
                            databaseSize = entryBytes
                        }
                    }
                    // Central directory + end-of-central-directory record are still
                    // unread: drain them so the tail buffer sees the real file end
                    val drainBuffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (raw.read(drainBuffer) != -1) {
                        // feedTail runs inside the wrapper
                    }
                }
            } catch (e: EOFException) {
                throw BackupValidationException("Backup archive is truncated", e)
            }
        }

        if (!databaseSeen) throw BackupValidationException("Backup archive is missing database.sqlite3 entry")
        if (databaseSize <= 0) throw BackupValidationException("Backup archive database.sqlite3 entry is empty")
        if (!hasEndOfCentralDirectory(tail, tailLength)) {
            throw BackupValidationException("Backup archive is truncated (no end-of-central-directory record)")
        }
        Timber.d("BackupArchiveValidator: archive validation OK")
    }

    /** Keeps the last [EOCD_MAX_SIZE] bytes of the stream in [tail]. */
    private fun feedTail(tail: ByteArray, tailLength: Int, buffer: ByteArray, offset: Int, count: Int): Int {
        if (count >= tail.size) {
            System.arraycopy(buffer, offset + count - tail.size, tail, 0, tail.size)
            return tail.size
        }
        val free = tail.size - tailLength
        if (count <= free) {
            System.arraycopy(buffer, offset, tail, tailLength, count)
            return tailLength + count
        }
        val drop = count - free
        System.arraycopy(tail, drop, tail, 0, tailLength - drop)
        System.arraycopy(buffer, offset, tail, tailLength - drop, count)
        return tail.size
    }

    /**
     * The end-of-central-directory record is the last thing written into an
     * archive: if one ends exactly at the end of the kept tail, the archive
     * was not truncated.
     */
    private fun hasEndOfCentralDirectory(tail: ByteArray, tailLength: Int): Boolean {
        var i = tailLength - 22
        while (i >= 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            ) {
                val commentLength = (tail[i + 20].toInt() and 0xFF) or ((tail[i + 21].toInt() and 0xFF) shl 8)
                if (i + 22 + commentLength == tailLength) return true
            }
            i--
        }
        return false
    }
}
