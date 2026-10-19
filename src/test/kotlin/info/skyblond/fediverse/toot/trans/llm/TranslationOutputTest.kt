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
        val parsed = parseTranslationOutput("""{"content": "Hello", "spoiler_text": "Warning"}""")
        assertEquals("Hello", parsed.content)
        assertEquals("Warning", parsed.spoilerText)
    }

    @Test
    fun `null absent and blank spoilers all resolve to null`() {
        for (answer in listOf(
            """{"content": "Hello", "spoiler_text": null}""",
            """{"content": "Hello"}""",
            """{"content": "Hello", "spoiler_text": ""}""",
            """{"content": "Hello", "spoiler_text": "   "}""",
        )) {
            val parsed = parseTranslationOutput(answer)
            assertEquals("Hello", parsed.content, answer)
            assertNull(parsed.spoilerText, answer)
        }
    }

    @Test
    fun `null absent and blank contents all resolve to null`() {
        for (answer in listOf(
            """{"content": null, "spoiler_text": "Warning"}""",
            """{"spoiler_text": "Warning"}""",
            """{"content": "", "spoiler_text": "Warning"}""",
            """{"content": "   ", "spoiler_text": "Warning"}""",
        )) {
            val parsed = parseTranslationOutput(answer)
            assertNull(parsed.content, answer)
            assertEquals("Warning", parsed.spoilerText, answer)
        }
    }

    @Test
    fun `an all-null answer parses as the nothing-to-translate answer`() {
        for (answer in listOf(
            """{"content": null, "spoiler_text": null}""",
            """{"content": "   ", "spoiler_text": ""}""",
            "{}",
        )) {
            val parsed = parseTranslationOutput(answer)
            assertNull(parsed.content, answer)
            assertNull(parsed.spoilerText, answer)
        }
    }

    @Test
    fun `a code fence wrapper is tolerated`() {
        val parsed = parseTranslationOutput(
            "```json\n{\"content\": \"Hallo\", \"spoiler_text\": null}\n```"
        )
        assertEquals("Hallo", parsed.content)
        assertNull(parsed.spoilerText)
    }

    @Test
    fun `unknown keys are ignored and values are trimmed`() {
        val parsed = parseTranslationOutput(
            """{"reasoning": "noise", "content": "  Hello  ", "spoiler_text": " W ", "extra": 1}"""
        )
        assertEquals("Hello", parsed.content)
        assertEquals("W", parsed.spoilerText)
    }

    @Test
    fun `newlines and unicode survive the JSON round trip`() {
        val parsed = parseTranslationOutput("""{"content": "line 1\nline 2 🌍", "spoiler_text": null}""")
        assertEquals("line 1\nline 2 🌍", parsed.content)
    }

    @Test
    fun `a non-object answer fails fast`() {
        for (answer in listOf("""["content"]""", "\"hello\"", "42", "null")) {
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
    fun `a field of any type but string and null fails fast`() {
        for (answer in listOf(
            """{"content": 42, "spoiler_text": "Warning"}""",
            """{"content": true, "spoiler_text": "Warning"}""",
            """{"content": ["a"], "spoiler_text": "Warning"}""",
            """{"content": {"a": 1}, "spoiler_text": "Warning"}""",
            """{"content": "Hello", "spoiler_text": 42}""",
            """{"content": "Hello", "spoiler_text": ["a"]}""",
        )) {
            assertFailsWith<TranslationOutputException>(answer) { parseTranslationOutput(answer) }
        }
    }

    @Test
    fun `an unclosed fence is not stripped and fails fast`() {
        // only a fence around the whole answer is dropped: a leading
        // fence without its closing one is left to the parser
        assertFailsWith<TranslationOutputException> {
            parseTranslationOutput("```json\n{\"content\": \"Hello\"}")
        }
    }
}
