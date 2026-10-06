package my.noveldokusha.core.appPreferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import my.noveldokusha.core.models.RegexRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты ПЕРСОНАЛЬНЫХ (per-novel) regexp-правил чистки текста.
 *
 * Проверяют путь `USER_REGEX_CLEANUP_RULES_PER_NOVEL` → `effectiveRegexRules(bookUrl)`:
 * сохранение флага `wholeWordsOnly` в JSON, слияние с глобальными правилами,
 * декодирование старых бэкапов без этого поля и связь хранения с поведением матчинга.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RegexRulePerNovelTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: AppPreferences

    private val bookUrl = "https://example.com/book1"

    @Before
    fun setUp() {
        prefs = AppPreferences(context)
        // Чистый старт: сбрасываем оба префа regexp-правил перед каждым тестом.
        prefs.USER_REGEX_CLEANUP_RULES.value = emptyList()
        prefs.USER_REGEX_CLEANUP_RULES_PER_NOVEL.value = emptyMap()
    }

    // ─── Roundtrip через SharedPreferences ─────────────────────────────────

    @Test
    fun `per novel rule keeps wholeWordsOnly after storage roundtrip`() {
        prefs.USER_REGEX_CLEANUP_RULES_PER_NOVEL.value = mapOf(
            bookUrl to listOf(
                RegexRule(pattern = "cat", replacement = "X", wholeWordsOnly = true)
            )
        )

        // Чтение обратно из префа — JSON-кодирование не должно терять поле.
        val stored = prefs.USER_REGEX_CLEANUP_RULES_PER_NOVEL.value.getValue(bookUrl).single()

        assertTrue(stored.wholeWordsOnly)
        assertEquals("cat", stored.pattern)
        assertEquals("X", stored.replacement)
    }

    // ─── Слияние глобальных и персональных правил ──────────────────────────

    @Test
    fun `effectiveRegexRules merges global and per novel rules with flags intact`() {
        val globalRule = RegexRule(pattern = "да", replacement = "G", wholeWordsOnly = false)
        val novelRule = RegexRule(pattern = "да", replacement = "N", wholeWordsOnly = true)

        prefs.USER_REGEX_CLEANUP_RULES.value = listOf(globalRule)
        prefs.USER_REGEX_CLEANUP_RULES_PER_NOVEL.value = mapOf(bookUrl to listOf(novelRule))

        // Порядок: сначала глобальные, потом персональные (контракт effectiveRegexRules).
        val merged = prefs.effectiveRegexRules(bookUrl)
        assertEquals(2, merged.size)
        assertEquals(globalRule, merged[0])
        assertEquals(novelRule, merged[1])
        assertFalse(merged[0].wholeWordsOnly)
        assertTrue(merged[1].wholeWordsOnly)

        // Чужая новелла получает только глобальные правила.
        val other = prefs.effectiveRegexRules("https://example.com/other")
        assertEquals(listOf(globalRule), other)
        assertFalse(other.single().wholeWordsOnly)
    }

    // ─── Старый JSON без wholeWordsOnly ────────────────────────────────────

    @Test
    fun `legacy json without wholeWordsOnly decodes to false`() {
        // Формат старых префов/бэкапов: поле wholeWordsOnly отсутствует.
        val legacyListJson =
            """[{"pattern":"cat","replacement":"X","isEnabled":true,"description":""}]"""
        val legacyMapJson =
            """{"https://example.com/book1":$legacyListJson}"""

        val list: List<RegexRule> = Json.decodeFromString(legacyListJson)
        assertFalse(list.single().wholeWordsOnly)

        val map: Map<String, List<RegexRule>> = Json.decodeFromString(legacyMapJson)
        assertFalse(map.getValue(bookUrl).single().wholeWordsOnly)
    }

    // ─── Хранение → поведение матчинга ─────────────────────────────────────

    @Test
    fun `per novel rule with flag produces whole word match`() {
        prefs.USER_REGEX_CLEANUP_RULES.value = listOf(
            RegexRule(pattern = "cat", replacement = "X", wholeWordsOnly = false)
        )
        prefs.USER_REGEX_CLEANUP_RULES_PER_NOVEL.value = mapOf(
            bookUrl to listOf(
                RegexRule(pattern = "cat", replacement = "X", wholeWordsOnly = true)
            )
        )

        val merged = prefs.effectiveRegexRules(bookUrl)
        val globalRule = merged[0]
        val novelRule = merged[1]

        val input = "cat category concat"

        // Персональное правило с флагом: только standalone-слово.
        assertTrue(novelRule.wholeWordsOnly)
        assertEquals(
            "X category concat",
            Regex(novelRule.effectivePattern).replace(input, novelRule.replacement)
        )

        // Глобальное правило без флага: каждое вхождение подстроки.
        assertFalse(globalRule.wholeWordsOnly)
        assertEquals(
            "X Xegory conX",
            Regex(globalRule.effectivePattern).replace(input, globalRule.replacement)
        )
    }
}
