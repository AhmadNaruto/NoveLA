package my.noveldokusha.core

/**
 * Content validation for chapter bodies.
 *
 * Returns an issue string (marker name or "too-short:<len>") when the text is
 * NOT chapter content, or null when it is valid.
 *
 * Marker tiers:
 * - HARD: technical tokens that cannot occur in prose (Cloudflare/CAPTCHA
 *   CSS class names) — rejected immediately.
 * - SOFT: natural-language phrases (interstitials, login walls, soft-404).
 *   They could theoretically be quoted in prose (LitRPG/UI quoting), and a
 *   false positive permanently deletes the chapter, while a missed wall is
 *   just one bad render — so they only reject with a short body (walls
 *   extract to a few hundred chars; observed login page was 86 chars).
 */

/** Impossible in prose: Cloudflare/CAPTCHA markup class names. */
private val HARD_MARKERS = listOf(
    "cf-content",
    "but-captcha",
    "cf-browser-verification",
    "challenge-running",
    "captcha-container",
    "hcaptcha",
)

/** Natural-language signals: reject only together with a short body. */
private val SOFT_MARKERS = listOf(
    // JS interstitial
    "please enable javascript",
    "verifying you are human",
    "checking your browser",
    // Login-wall wording (vetoed as FP-prone in prose: "forgot your
    // password", "page not found", "create an account")
    "use your email and password",
    "sign in to continue",
    "log in to continue",
    "start reading with a guest account",
    // Soft-404
    "chapter not found",
    "chapter does not exist",
)

/** Below this length a SOFT marker is treated as self-sufficient. */
private const val SOFT_MARKER_MAX_LENGTH = 800

fun chapterContentIssue(text: String): String? {
    if (text.length < 100) return "too-short:${text.length}"

    val lowerText = text.lowercase()
    HARD_MARKERS.firstOrNull { lowerText.contains(it) }?.let { return it }
    if (text.length < SOFT_MARKER_MAX_LENGTH) {
        SOFT_MARKERS.firstOrNull { lowerText.contains(it) }?.let { return it }
    }
    return null
}

/** Check if content is valid for processing. */
fun isValidChapterContent(text: String): Boolean = chapterContentIssue(text) == null
