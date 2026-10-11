package my.noveldokusha.tooling.lexbor_native

import java.io.Closeable

class LexborDocument internal constructor(
    val docPtr: Long,
    rootPtr: Long
) : LexborElement(rootPtr), Closeable {

    override fun close() {
        if (docPtr != 0L) {
            LexborNative.nativeFreeDocument(docPtr)
        }
    }

    companion object {
        fun parse(html: String): LexborDocument {
            val docPtr = LexborNative.nativeParse(html)
            require(docPtr != 0L) { "Failed to parse HTML using Lexbor native engine" }
            val rootPtr = LexborNative.nativeGetRootNode(docPtr)
            return LexborDocument(docPtr, rootPtr)
        }
    }
}
