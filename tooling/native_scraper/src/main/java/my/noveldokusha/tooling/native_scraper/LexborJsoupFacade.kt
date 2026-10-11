package my.noveldokusha.tooling.native_scraper

import java.util.regex.Pattern

object LexborJsoupFacade {
    fun parse(html: String): LexborDocument = LexborDocument.parse(html)

    fun parse(html: String, baseUri: String): LexborDocument = LexborDocument.parse(html, baseUri)

    fun parseBodyFragment(bodyHtml: String, baseUri: String = ""): LexborDocument {
        val wrapped = "<html><head></head><body>$bodyHtml</body></html>"
        return LexborDocument.parse(wrapped, baseUri)
    }

    private val NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);")

    fun unescapeEntities(string: String, inAttribute: Boolean = false): String {
        if (!string.contains('&')) return string

        val sb = StringBuilder(string.length)
        val matcher = NUMERIC_ENTITY_PATTERN.matcher(string)
        var lastEnd = 0

        while (matcher.find()) {
            sb.append(string, lastEnd, matcher.start())
            val entityCode = matcher.group(1)
            try {
                val codePoint = if (entityCode.startsWith("x") || entityCode.startsWith("X")) {
                    entityCode.substring(1).toInt(16)
                } else {
                    entityCode.toInt(10)
                }
                sb.append(Character.toChars(codePoint))
            } catch (_: Exception) {
                sb.append(matcher.group(0))
            }
            lastEnd = matcher.end()
        }
        sb.append(string, lastEnd, string.length)

        var result = sb.toString()
        result = result
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&nbsp;", "\u00A0")

        return result
    }
}
