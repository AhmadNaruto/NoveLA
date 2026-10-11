package my.noveldokusha.tooling.lexbor_native

object LexborJsoupFacade {
    fun parse(html: String): LexborDocument = LexborDocument.parse(html)
}
