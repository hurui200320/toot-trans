package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.client.ChatApiException
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionException
import info.skyblond.fediverse.toot.trans.llm.client.ChatContent
import info.skyblond.fediverse.toot.trans.llm.client.ContentPart
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.statuses.original
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The ladder and answer policy of [Translator]: rich-to-bare
 * downgrades on payload-shaped 4xx, no downgrade on
 * credential/retryable failures, and the spoiler fallback.
 * */
class TranslatorTest {
    private val client = FakeChatClient()
    private val translator = Translator(
        client,
        TranslationRequestFactory("English", "test-model", null, 100, null),
    )

    private fun entry(id: String, text: String, spoilerText: String = ""): TranslationContextEntry =
        TranslationContextEntry(
            status = original(id).copy(spoilerText = spoilerText.takeIf { it.isNotBlank() }),
            source = StatusSource(id, text, spoilerText),
        )

    private fun context(
        withImages: Boolean = false,
        withHistory: Boolean = false,
    ): TranslationContext = TranslationContext(
        entries = buildList {
            if (withHistory) add(entry("root", "root text"))
            add(entry("P", "target text"))
        },
        images = if (withImages) listOf(ContentPart.image(byteArrayOf(1), "image/png")) else emptyList(),
    )

    @Test
    fun `a valid answer parses without downgrading`() {
        runTest {
            client.answerWith(translationAnswer("Hello"))
            val translated = translator.translate(context())
            assertEquals(TranslatedToot("Hello", null), translated)
            assertEquals(1, client.requests.size)
        }
    }

    @Test
    fun `an image context walks the full ladder before failing`() {
        runTest {
            client.handler = { throw ChatApiException(400, "no vision") }
            val error = assertFailsWith<ChatApiException> {
                translator.translate(context(withImages = true, withHistory = true))
            }
            assertEquals(400, error.httpCode)

            // full, text, minimal: the rich payload first, the toot-only last
            assertEquals(3, client.requests.size)
            assertIs<ChatContent.Parts>(client.requests[0].messages.last().content)
            assertIs<ChatContent.Text>(client.requests[1].messages.last().content)
            assertEquals(4, client.requests[0].messages.size)
            assertEquals(2, client.requests[2].messages.size)
        }
    }

    @Test
    fun `every payload-shaped rejection downgrades once and the next rung answers`() {
        runTest {
            for (code in listOf(400, 413, 415, 422)) {
                val failing = FakeChatClient()
                var calls = 0
                failing.handler = {
                    if (calls++ == 0) throw ChatApiException(code, "payload rejected")
                    completionResponse(translationAnswer("Hello"))
                }
                val failingTranslator = Translator(
                    failing,
                    TranslationRequestFactory("English", "test-model", null, 100, null),
                )

                val translated = failingTranslator.translate(context(withImages = true))

                assertEquals("Hello", translated.translation, "HTTP $code")
                assertEquals(2, failing.requests.size, "HTTP $code must downgrade exactly once")
            }
        }
    }

    @Test
    fun `credential and retryable failures propagate without downgrading`() {
        runTest {
            for (code in listOf(401, 402, 403, 404, 408, 429, 500)) {
                val failing = FakeChatClient()
                failing.handler = { throw ChatApiException(code, "nope") }
                val failingTranslator = Translator(
                    failing,
                    TranslationRequestFactory("English", "test-model", null, 100, null),
                )
                val error = assertFailsWith<ChatApiException> {
                    failingTranslator.translate(context(withImages = true))
                }
                assertEquals(code, error.httpCode)
                assertEquals(1, failing.requests.size, "HTTP $code must not downgrade")
            }
        }
    }

    @Test
    fun `an image-less context ends after the minimal rung`() {
        runTest {
            client.handler = { throw ChatApiException(400, "rejected") }
            assertFailsWith<ChatApiException> { translator.translate(context()) }
            assertEquals(2, client.requests.size)
        }
    }

    @Test
    fun `an unusable answer fails as an output exception`() {
        runTest {
            client.answerWith("Sure! The translation is: Hello")
            assertFailsWith<TranslationOutputException> { translator.translate(context()) }
            // the answer, not the payload, was the problem: no downgrade
            assertEquals(1, client.requests.size)
        }
    }

    @Test
    fun `a blank completion fails as a completion exception`() {
        runTest {
            client.answerWith("   ")
            assertFailsWith<ChatCompletionException> { translator.translate(context()) }
            assertEquals(1, client.requests.size)
        }
    }

    @Test
    fun `an omitted content warning falls back to the original`() {
        runTest {
            client.answerWith(translationAnswer("Hello"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", "target text", spoilerText = "警告")))
            )
            assertEquals("警告", translated.spoilerText)
        }
    }

    @Test
    fun `the answered content warning is used when present`() {
        runTest {
            client.answerWith(translationAnswer("Hello", spoilerText = "Warning"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", "target text", spoilerText = "警告")))
            )
            assertEquals("Warning", translated.spoilerText)
        }
    }

    @Test
    fun `a stray warning for a warning-less source is dropped`() {
        runTest {
            client.answerWith(translationAnswer("Hello", spoilerText = "stray"))
            val translated = translator.translate(context())
            assertNull(translated.spoilerText)
        }
    }

    @Test
    fun `a content-warning-only source turns its answered body into the warning`() {
        runTest {
            // the request sends the warning as the text, so the answered
            // body is the translated warning
            client.answerWith(translationAnswer("Warning"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", text = "", spoilerText = "警告")))
            )
            assertEquals("Warning", translated.spoilerText)
            assertEquals("", translated.translation)
        }
    }

    @Test
    fun `an answered warning wins for a content-warning-only source`() {
        runTest {
            client.answerWith(translationAnswer("Warning", spoilerText = "Notice"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", text = "", spoilerText = "警告")))
            )
            assertEquals("Notice", translated.spoilerText)
            assertEquals("", translated.translation)
        }
    }
}
