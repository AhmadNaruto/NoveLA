package my.noveldokusha.core.models

import kotlinx.serialization.Serializable

@Serializable
data class RegexRule(
    val pattern: String,
    val replacement: String = "",
    val isEnabled: Boolean = true,
    val description: String = "",
    val wholeWordsOnly: Boolean = false
) {
    /**
     * Pattern actually used for matching.
     *
     * Word boundaries are added with lookarounds instead of `\b` because `\b` is
     * ASCII-only on desktop JVM, while Android matches through ICU where it is
     * Unicode-aware - the same pattern must behave identically in both places.
     */
    val effectivePattern: String
        get() = if (wholeWordsOnly) "(?<![\\p{L}\\p{N}_])(?:$pattern)(?![\\p{L}\\p{N}_])" else pattern
}