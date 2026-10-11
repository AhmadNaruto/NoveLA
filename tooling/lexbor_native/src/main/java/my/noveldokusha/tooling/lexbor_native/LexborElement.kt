package my.noveldokusha.tooling.lexbor_native

open class LexborElement(nodePtr: Long) : LexborNode(nodePtr) {
    fun select(cssQuery: String): LexborElements {
        val ptrs = LexborNative.nativeSelect(nodePtr, cssQuery) ?: longArrayOf()
        return LexborElements(ptrs.map { LexborElement(it) })
    }

    fun selectFirst(cssQuery: String): LexborElement? {
        val ptr = LexborNative.nativeSelectFirst(nodePtr, cssQuery)
        return if (ptr != 0L) LexborElement(ptr) else null
    }
}

class LexborElements(elements: List<LexborElement> = emptyList()) : ArrayList<LexborElement>(elements) {
    fun text(): String = joinToString(" ") { it.text() }.trim()
    fun attr(attributeKey: String): String = firstOrNull()?.attr(attributeKey).orEmpty()
    fun select(cssQuery: String): LexborElements {
        val result = mutableListOf<LexborElement>()
        for (element in this) {
            result.addAll(element.select(cssQuery))
        }
        return LexborElements(result)
    }
}
