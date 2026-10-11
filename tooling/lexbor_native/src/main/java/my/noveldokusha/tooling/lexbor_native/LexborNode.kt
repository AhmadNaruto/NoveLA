package my.noveldokusha.tooling.lexbor_native

open class LexborNode(val nodePtr: Long) {
    fun text(): String = LexborNative.nativeGetText(nodePtr)
    fun attr(attributeKey: String): String = LexborNative.nativeGetAttr(nodePtr, attributeKey)
    fun remove() = LexborNative.nativeRemoveNode(nodePtr)
    fun outerHtml(): String = LexborNative.nativeGetOuterHtml(nodePtr)
    fun tagName(): String = LexborNative.nativeGetTagName(nodePtr)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LexborNode) return false
        return nodePtr == other.nodePtr
    }

    override fun hashCode(): Int = nodePtr.hashCode()
}
