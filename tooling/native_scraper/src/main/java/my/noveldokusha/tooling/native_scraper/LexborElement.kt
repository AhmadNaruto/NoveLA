package my.noveldokusha.tooling.native_scraper

open class LexborElement(
    nodePtr: Long,
    ownerDocument: LexborDocument? = null
) : LexborNode(nodePtr, ownerDocument) {

    fun select(cssQuery: String): LexborElements {
        val ptrs = LexborNative.nativeSelect(nodePtr, cssQuery) ?: longArrayOf()
        val doc = ownerDocument ?: (this as? LexborDocument)
        return LexborElements(ptrs.map { LexborElement(it, doc) })
    }

    fun selectFirst(cssQuery: String): LexborElement? {
        val ptr = LexborNative.nativeSelectFirst(nodePtr, cssQuery)
        val doc = ownerDocument ?: (this as? LexborDocument)
        return if (ptr != 0L) LexborElement(ptr, doc) else null
    }

    fun children(): LexborElements {
        val doc = ownerDocument ?: (this as? LexborDocument)
        val elements = childNodes().filterIsInstance<LexborElement>()
        return LexborElements(elements)
    }

    fun id(): String = attr("id")

    fun className(): String = attr("class")

    fun hasClass(className: String): Boolean {
        val classes = className().split(Regex("""\s+"""))
        return classes.contains(className)
    }
}

class LexborElements(elements: List<LexborElement> = emptyList()) : ArrayList<LexborElement>(elements) {

    fun text(): String = joinToString(" ") { it.text() }.trim()

    fun html(): String = firstOrNull()?.html().orEmpty()

    fun outerHtml(): String = joinToString("\n") { it.outerHtml() }

    fun attr(attributeKey: String): String = firstOrNull()?.attr(attributeKey).orEmpty()

    fun remove(): LexborElements {
        for (element in this) {
            element.remove()
        }
        return this
    }

    fun select(cssQuery: String): LexborElements {
        val result = mutableListOf<LexborElement>()
        for (element in this) {
            result.addAll(element.select(cssQuery))
        }
        return LexborElements(result)
    }

    fun selectFirst(cssQuery: String): LexborElement? {
        for (element in this) {
            val found = element.selectFirst(cssQuery)
            if (found != null) return found
        }
        return null
    }
}
