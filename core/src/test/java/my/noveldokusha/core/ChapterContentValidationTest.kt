package my.noveldokusha.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterContentValidationTest {

    private val normalChapter = buildString {
        append("Chapter 2. Qi Regulating Pill. ")
        append("The morning light filtered through the bamboo curtains as he opened his eyes. ")
        append("He had cultivated for three days without sleep, and now the spiritual energy ")
        append("finally settled into his dantian. The pill glowed faintly in his palm. ")
        append("He swallowed it and felt warmth spread through every meridian in his body.")
    }

    @Test
    fun `short text is invalid`() {
        assertFalse(isValidChapterContent("Sign In\nUse your email"))
    }

    @Test
    fun `normal chapter text is valid`() {
        assertTrue(isValidChapterContent(normalChapter))
    }

    @Test
    fun `login page is invalid`() {
        val loginPage = "Sign In\n\n\n\n\n Use your email and password, Google, or start reading " +
            "with a guest account. Email Password Forgot your password? Create an account " +
            "and continue reading the novel right now after authentication."
        assertFalse(isValidChapterContent(loginPage))
    }

    @Test
    fun `soft 404 page is invalid`() {
        val soft404 = "Oops! Chapter not found. The page you are looking for might have been " +
            "removed, had its name changed, or is temporarily unavailable. Please go back " +
            "to the table of contents and pick another chapter from the list."
        assertFalse(isValidChapterContent(soft404))
    }

    @Test
    fun `cloudflare interstitial is invalid`() {
        val interstitial = "Just a moment... Please enable JavaScript to continue. " +
            "Verifying you are human. Checking your browser before accessing the website. " +
            "This process is automatic and will take a few seconds to complete."
        assertFalse(isValidChapterContent(interstitial))
    }

    // Регрессия: эти фразы встречаются в реальной прозе (Royal Reboot, Metaworld
    // Chronicles) и не должны отбраковывать настоящие главы.

    @Test
    fun `dialogue about password reset is valid`() {
        val prose = "He stared at the screen. \"Wait, seriously? You forgot your password? " +
            "After all this time?\" She shrugged and reached for the phone, tapping the " +
            "little key icon. \"Just create an account again,\" he muttered, \"or reset it, " +
            "whatever works faster.\" Outside the café window the rain kept falling."
        assertTrue(isValidChapterContent(prose))
    }

    @Test
    fun `page not found phrase in prose is valid`() {
        val prose = "The forum post was old and full of dead links, every second thread " +
            "complaining about a page not found error after the migration. He scrolled past " +
            "the complaints and finally found the archived thread with the guide he needed, " +
            "complete with screenshots and a working mirror link."
        assertTrue(isValidChapterContent(prose))
    }

    // Soft-маркеры: в одиночку в длинном тексте не режут, только с коротким телом.

    @Test
    fun `soft marker alone in long text is valid`() {
        val longProse = "The quest log updated with a new entry: chapter not found in the " +
            "archive, the scribe explained, because the royal library had burned centuries " +
            "ago. " + "He walked between the empty shelves counting the ash marks. ".repeat(30)
        assertTrue(longProse.length > 800)
        assertTrue(isValidChapterContent(longProse))
    }

    @Test
    fun `soft marker with short body is invalid`() {
        val shortError = "Oops! Chapter does not exist. The link you followed is outdated " +
            "or the chapter was removed by the author. Please return to the table of " +
            "contents."
        assertTrue(shortError.length < 800)
        assertFalse(isValidChapterContent(shortError))
    }

    // Интерстишал/login-фразы теперь SOFT: в длинной главе, цитирующей UI, не режут.

    @Test
    fun `interstitial phrase quoted in long chapter is valid`() {
        val longProse = "The system prompt glowed: verifying you are human, checking your " +
            "browser before access is granted. \"Use your email and password, or start " +
            "reading with a guest account,\" the tutorial said, \"then sign in to continue " +
            "the trial.\" He did, and the gate opened. " +
            "The corridor lights flickered as he walked deeper. ".repeat(30)
        assertTrue(longProse.length > 800)
        assertTrue(isValidChapterContent(longProse))
    }

    // chapterContentIssue: имя сработавшего маркера для логов.

    @Test
    fun `chapterContentIssue reports marker name`() {
        assertEquals(
            "use your email and password",
            chapterContentIssue(
                "Sign In Use your email and password, Google, or start reading with a " +
                    "guest account right now after successful authentication on the site."
            )
        )
    }

    @Test
    fun `chapterContentIssue reports too short`() {
        val issue = chapterContentIssue("Sign In\nUse your email")
        assertTrue("expected too-short issue, got: $issue", issue!!.startsWith("too-short:"))
    }

    @Test
    fun `chapterContentIssue returns null for valid text`() {
        assertEquals(null, chapterContentIssue(normalChapter))
    }
}
