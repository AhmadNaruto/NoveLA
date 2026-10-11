package my.noveldokusha.tooling.native_scraper

import java.net.URI

open class LexborNode(
    val nodePtr: Long,
    open val ownerDocument: LexborDocument? = null
) {
    open fun nodeName(): String = LexborNative.nativeGetNodeName(nodePtr)

    val nodeType: Int get() = LexborNative.nativeGetNodeType(nodePtr)

    val isTextNode: Boolean get() = nodeType == 3

    open fun text(): String = LexborNative.nativeGetText(nodePtr)

    open fun attr(attributeKey: String): String {
        if (attributeKey.startsWith("abs:")) {
            val realKey = attributeKey.substring(4)
            val raw = LexborNative.nativeGetAttr(nodePtr, realKey).trim()
            if (raw.isEmpty()) return ""
            val base = ownerDocument?.baseUri.orEmpty()
            if (base.isEmpty()) return raw
            return resolveRelativeUrl(base, raw)
        }
        return LexborNative.nativeGetAttr(nodePtr, attributeKey)
    }

    fun hasAttr(attributeKey: String): Boolean = attr(attributeKey).isNotEmpty()

    fun remove() = LexborNative.nativeRemoveNode(nodePtr)

    open fun outerHtml(): String = LexborNative.nativeGetOuterHtml(nodePtr)

    open fun html(): String = LexborNative.nativeGetInnerHtml(nodePtr)

    open fun tagName(): String = LexborNative.nativeGetTagName(nodePtr)

    fun childNodes(): List<LexborNode> {
        val ptrs = LexborNative.nativeGetChildNodes(nodePtr) ?: return emptyList()
        val doc = ownerDocument ?: (this as? LexborDocument)
        return ptrs.map { ptr ->
            val type = LexborNative.nativeGetNodeType(ptr)
            when (type) {
                1 -> LexborElement(ptr, doc)
                3 -> LexborTextNode(ptr, doc)
                else -> LexborNode(ptr, doc)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LexborNode) return false
        return nodePtr == other.nodePtr
    }

    override fun hashCode(): Int = nodePtr.hashCode()

    companion object {
        fun resolveRelativeUrl(base: String, rel: String): String {
            return try {
                if (rel.startsWith("http://", ignoreCase = true) || rel.startsWith("https://", ignoreCase = true)) {
                    rel
                } else if (rel.startsWith("//")) {
                    val scheme = URI(base).scheme ?: "https"
                    "$scheme:$rel"
                } else {
                    URI(base).resolve(rel).toString()
                }
            } catch (_: Exception) {
                rel
            }
        }
    }
}

class LexborTextNode(nodePtr: Long, ownerDocument: LexborDocument? = null) : LexborNode(nodePtr, ownerDocument) {
    override fun nodeName(): String = "#text"
}
