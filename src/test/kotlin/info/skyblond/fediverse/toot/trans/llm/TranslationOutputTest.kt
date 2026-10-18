package info.skyblond.fediverse.toot.trans.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The strict-but-tolerant parser of the translation answer contract,
 * see [parseTranslationOutput].
 * */
class TranslationOutputTest {

    @Test
    fun `a plain contract object parses`() {
        val parsed = parseTranslationOutput("""{"translation": "Hello", "spoiler_text": "Warning"}""")
        assertEquals("Hello", parsed.translation)
        assertEquals("Warning", parsed.spoilerText)
    }

    @Test
    fun `null absent and blank spoilers all resolve to null`() {
        for (answer in listOf(
            """{"translation": "Hello", "spoiler_text": null}""",
            """{"translation": "Hello"}""",
            """{"translation": "Hello", "spoiler_text": ""}""",
            """{"translation": "Hello", "spoiler_text": "   "}""",
        )) {
            assertNull(parseTranslationOutput(answer).spoilerText, answer)
        }
    }

    @Test
    fun `a code fence wrapper is tolerated`() {
        val parsed = parseTranslationOutput(
            "```json\n{\"translation\": \"Hallo\", \"spoiler_text\": null}\n```"
        )
        assertEquals("Hallo", parsed.translation)
        assertNull(parsed.spoilerText)
    }

    @Test
    fun `unknown keys are ignored and values are trimmed`() {
        val parsed = parseTranslationOutput(
            """{"reasoning": "noise", "translation": "  Hello  ", "spoiler_text": " W ", "extra": 1}"""
        )
        assertEquals("Hello", parsed.translation)
        assertEquals("W", parsed.spoilerText)
    }

    @Test
    fun `newlines and unicode survive the JSON round trip`() {
        val parsed = parseTranslationOutput("""{"translation": "line 1\nline 2 🌍", "spoiler_text": null}""")
        assertEquals("line 1\nline 2 🌍", parsed.translation)
    }

    @Test
    fun `a non-object answer fails fast`() {
        for (answer in listOf("""["translation"]""", "\"hello\"", "42", "null")) {
            assertFailsWith<TranslationOutputException>(answer) { parseTranslationOutput(answer) }
        }
    }

    @Test
    fun `a non-json answer fails fast with a snippet`() {
        val error = assertFailsWith<TranslationOutputException> {
            parseTranslationOutput("Sure! Here is the translation: Hello")
        }
        assertTrue(error.message!!.contains("not valid JSON"))
        assertTrue(error.message!!.contains("Sure!"))
    }

    @Test
    fun `a missing or blank translation fails fast`() {
        for (answer in listOf(
            """{"spoiler_text": "Warning"}""",
            """{"translation": null}""",
            """{"translation": "   "}""",
            """{"translation": 42}""",
        )) {
            assertFailsWith<TranslationOutputException>(answer) { parseTranslationOutput(answer) }
        }
    }

    @Test
    fun `an unclosed fence is not stripped and fails fast`() {
        // only a fence around the whole answer is dropped: a leading
        // fence without its closing one is left to the parser
        assertFailsWith<TranslationOutputException> {
            parseTranslationOutput("```json\n{\"translation\": \"Hello\"}")
        }
    }
}
