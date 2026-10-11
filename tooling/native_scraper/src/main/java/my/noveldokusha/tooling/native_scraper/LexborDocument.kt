package my.noveldokusha.tooling.native_scraper

import java.io.Closeable
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.AtomicBoolean

class LexborDocument internal constructor(
    val docPtr: Long,
    rootPtr: Long,
    var baseUri: String = ""
) : LexborElement(rootPtr), Closeable {

    override val ownerDocument: LexborDocument get() = this

    private val cleanerAction = DocumentCleanerAction(docPtr)
    private val cleanable: Cleaner.Cleanable = cleaner.register(this, cleanerAction)

    fun body(): LexborElement {
        val ptr = LexborNative.nativeGetBodyNode(docPtr)
        return if (ptr != 0L) LexborElement(ptr, this) else this
    }

    fun head(): LexborElement {
        val ptr = LexborNative.nativeGetHeadNode(docPtr)
        return if (ptr != 0L) LexborElement(ptr, this) else this
    }

    fun title(): String = LexborNative.nativeGetTitle(docPtr)

    fun location(): String = baseUri

    fun setBaseUri(uri: String): LexborDocument {
        this.baseUri = uri
        return this
    }

    override fun close() {
        cleanable.clean()
    }

    private class DocumentCleanerAction(private val ptr: Long) : Runnable {
        private val closed = AtomicBoolean(false)

        override fun run() {
            if (closed.compareAndSet(false, true)) {
                if (ptr != 0L) {
                    LexborNative.nativeFreeDocument(ptr)
                }
            }
        }
    }

    companion object {
        private val cleaner = Cleaner.create()

        fun parse(html: String, baseUri: String = ""): LexborDocument {
            val docPtr = LexborNative.nativeParse(html)
            require(docPtr != 0L) { "Failed to parse HTML using Lexbor native engine" }
            val rootPtr = LexborNative.nativeGetRootNode(docPtr)
            return LexborDocument(docPtr, rootPtr, baseUri)
        }
    }
}
