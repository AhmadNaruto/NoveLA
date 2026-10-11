package my.noveldokusha.tooling.native_scraper

object LexborNative {
    init {
        try {
            System.loadLibrary("native_scraper")
        } catch (e: UnsatisfiedLinkError) {
            e.printStackTrace()
        }
    }

    @JvmStatic
    external fun nativeParse(html: String): Long

    @JvmStatic
    external fun nativeGetRootNode(docPtr: Long): Long

    @JvmStatic
    external fun nativeGetBodyNode(docPtr: Long): Long

    @JvmStatic
    external fun nativeGetHeadNode(docPtr: Long): Long

    @JvmStatic
    external fun nativeGetTitle(docPtr: Long): String

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
    external fun nativeGetInnerHtml(nodePtr: Long): String

    @JvmStatic
    external fun nativeGetTagName(nodePtr: Long): String

    @JvmStatic
    external fun nativeGetNodeType(nodePtr: Long): Int

    @JvmStatic
    external fun nativeGetNodeName(nodePtr: Long): String

    @JvmStatic
    external fun nativeGetChildNodes(nodePtr: Long): LongArray?

    @JvmStatic
    external fun nativeGetParentNode(nodePtr: Long): Long

    @JvmStatic
    external fun nativeGetNextSibling(nodePtr: Long): Long

    @JvmStatic
    external fun nativeGetPrevSibling(nodePtr: Long): Long

    @JvmStatic
    external fun nativeGetNextElementSibling(nodePtr: Long): Long

    @JvmStatic
    external fun nativeGetPrevElementSibling(nodePtr: Long): Long
}
