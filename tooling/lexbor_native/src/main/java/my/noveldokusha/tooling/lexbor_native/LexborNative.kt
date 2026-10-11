package my.noveldokusha.tooling.lexbor_native

object LexborNative {
    init {
        try {
            System.loadLibrary("lexbor_jni")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun nativeParse(html: String): Long

    @JvmStatic
    external fun nativeGetRootNode(docPtr: Long): Long

    @JvmStatic
    external fun nativeFreeDocument(docPtr: Long)

    @JvmStatic
    external fun nativeSelect(nodePtr: Long, cssQuery: String): LongArray?

    @JvmStatic
    external fun nativeSelectFirst(nodePtr: Long, cssQuery: String): Long

    @JvmStatic
    external fun nativeGetText(nodePtr: Long): String

    @JvmStatic
    external fun nativeGetAttr(nodePtr: Long, attrName: String): String

    @JvmStatic
    external fun nativeRemoveNode(nodePtr: Long)

    @JvmStatic
    external fun nativeGetOuterHtml(nodePtr: Long): String

    @JvmStatic
    external fun nativeGetTagName(nodePtr: Long): String
}
