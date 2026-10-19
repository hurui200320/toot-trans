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
import kotlin.test.assertTrue

/**
 * The fallback sequence and answer policy of [Translator]: rich-to-bare
 * downgrades on payload-shaped 4xx, no downgrade on
 * credential/retryable failures, and the answer's source-shape policy.
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
    fun `an image context walks the whole fallback sequence before failing`() {
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
    fun `every payload-shaped rejection downgrades once and the next variant answers`() {
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

                assertEquals("Hello", translated.content, "HTTP $code")
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
    fun `an image-less context ends after the minimal variant`() {
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
            // the answer, not the payload, was the problem: every attempt
            // retries the variant, and text + minimal are exhausted in turn
            assertEquals(8, client.requests.size)
        }
    }

    @Test
    fun `a blank completion fails as a completion exception`() {
        runTest {
            client.answerWith("   ")
            assertFailsWith<ChatCompletionException> { translator.translate(context()) }
            // the same attempt budget applies to a blank completion
            assertEquals(8, client.requests.size)
        }
    }

    @Test
    fun `an image context exhausts the answer attempts of all three variants`() {
        runTest {
            client.answerWith("Sure! The translation is: Hello")
            assertFailsWith<TranslationOutputException> {
                translator.translate(context(withImages = true, withHistory = true))
            }
            // four attempts each of full, text and minimal, in turn
            assertEquals(12, client.requests.size)
            assertIs<ChatContent.Parts>(client.requests[0].messages.last().content)
            assertIs<ChatContent.Text>(client.requests[4].messages.last().content)
            assertEquals(2, client.requests[8].messages.size)
        }
    }

    @Test
    fun `an unusable answer is retried before the variant succeeds`() {
        runTest {
            var calls = 0
            client.handler = {
                if (calls++ < 2) {
                    completionResponse("Sure! The translation is: Hello")
                } else {
                    completionResponse(translationAnswer("Hello"))
                }
            }
            val translated = translator.translate(context(withHistory = true))
            assertEquals("Hello", translated.content)
            // the two failures retried the text variant (4-message request);
            // the minimal variant (2 messages) was never reached
            assertEquals(3, client.requests.size)
            assertTrue(client.requests.all { it.messages.size == 4 })
        }
    }

    @Test
    fun `a blank completion is retried on the same variant`() {
        runTest {
            var calls = 0
            client.handler = {
                if (calls++ == 0) completionResponse("   ") else completionResponse(translationAnswer("Hello"))
            }
            val translated = translator.translate(context())
            assertEquals("Hello", translated.content)
            assertEquals(2, client.requests.size)
        }
    }

    @Test
    fun `a content-filtered completion skips the retries and downgrades`() {
        runTest {
            client.handler = { request ->
                if (request.messages.size > 2) {
                    // the text variant is content-filtered on every attempt
                    completionResponse("", finishReason = "content_filter")
                } else {
                    completionResponse(translationAnswer("Hello"))
                }
            }
            val translated = translator.translate(context(withHistory = true))
            assertEquals("Hello", translated.content)
            // the filter is deterministic for the same payload: one attempt
            // of the text variant, then straight to the minimal variant
            assertEquals(2, client.requests.size)
            assertEquals(2, client.requests.last().messages.size)
        }
    }

    @Test
    fun `a content-filtered answer on the last variant fails without retrying`() {
        runTest {
            client.handler = { completionResponse("", finishReason = "content_filter") }
            val error = assertFailsWith<ChatCompletionException> { translator.translate(context()) }
            assertEquals("content_filter", error.finishReason)
            // one filtered attempt each of text and minimal, no retries
            assertEquals(2, client.requests.size)
        }
    }

    @Test
    fun `an exhausted answer failure downgrades to the next variant`() {
        runTest {
            client.handler = { request ->
                if (request.messages.size > 2) {
                    completionResponse("Sure! The translation is: Hello")
                } else {
                    completionResponse(translationAnswer("Hello"))
                }
            }
            val translated = translator.translate(context(withHistory = true))
            assertEquals("Hello", translated.content)
            // four attempts of the text variant (the initial call plus three
            // retries), then the minimal variant answered
            assertEquals(5, client.requests.size)
            assertEquals(2, client.requests.last().messages.size)
        }
    }

    @Test
    fun `a payload rejection after an answer failure downgrades without spending the remaining attempts`() {
        runTest {
            var calls = 0
            client.handler = {
                when (calls++) {
                    0 -> completionResponse("Sure! The translation is: Hello")
                    1 -> throw ChatApiException(400, "rejected")
                    else -> completionResponse(translationAnswer("Hello"))
                }
            }
            val translated = translator.translate(context(withImages = true, withHistory = true))
            assertEquals("Hello", translated.content)
            // one failure retried the full variant, the rejection downgraded
            // right away: the text variant answered with three attempts to spare
            assertEquals(3, client.requests.size)
            assertIs<ChatContent.Text>(client.requests.last().messages.last().content)
            assertEquals(4, client.requests.last().messages.size)
        }
    }

    @Test
    fun `an answer without the source content warning fails after the retries`() {
        runTest {
            client.answerWith(translationAnswer("Hello"))
            val error = assertFailsWith<TranslationOutputException> {
                translator.translate(
                    TranslationContext(listOf(entry("P", "target text", spoilerText = "警告")))
                )
            }
            // the failure carries a snippet of the offending answer
            assertTrue(error.message.orEmpty().contains("\"spoiler_text\":null"))
            // the missing warning survives every retry and both variants
            assertEquals(8, client.requests.size)
        }
    }

    @Test
    fun `the answered content warning is used when present`() {
        runTest {
            client.answerWith(translationAnswer("Hello", spoilerText = "Warning"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", "target text", spoilerText = "警告")))
            )
            assertEquals("Hello", translated.content)
            assertEquals("Warning", translated.spoilerText)
        }
    }

    @Test
    fun `a stray warning for a warning-less source is dropped`() {
        runTest {
            client.answerWith(translationAnswer("Hello", spoilerText = "stray"))
            val translated = translator.translate(context())
            assertEquals("Hello", translated.content)
            assertNull(translated.spoilerText)
        }
    }

    @Test
    fun `a content-warning-only source takes the answered warning and nulls the content`() {
        runTest {
            client.answerWith(translationAnswer(null, spoilerText = "Warning"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", text = "", spoilerText = "警告")))
            )
            assertNull(translated.content)
            assertEquals("Warning", translated.spoilerText)
        }
    }

    @Test
    fun `an answered body for a body-less source is nulled out`() {
        runTest {
            client.answerWith(translationAnswer("Warning", spoilerText = "Notice"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", text = "", spoilerText = "警告")))
            )
            assertNull(translated.content)
            assertEquals("Notice", translated.spoilerText)
        }
    }

    @Test
    fun `an answer without content for a source with a body fails after the retries`() {
        runTest {
            client.answerWith(translationAnswer(null))
            val error = assertFailsWith<TranslationOutputException> { translator.translate(context()) }
            // the failure carries a snippet of the offending answer
            assertTrue(error.message.orEmpty().contains("\"content\":null"))
            assertEquals(8, client.requests.size)
        }
    }

    @Test
    fun `an all-null answer for a toot with nothing to translate is kept`() {
        runTest {
            client.answerWith(translationAnswer(null))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", text = "")))
            )
            assertNull(translated.content)
            assertNull(translated.spoilerText)
        }
    }

    @Test
    fun `an answered body and warning for a toot with nothing to translate are nulled out`() {
        runTest {
            client.answerWith(translationAnswer("Hello", spoilerText = "Warning"))
            val translated = translator.translate(
                TranslationContext(listOf(entry("P", text = "")))
            )
            assertNull(translated.content)
            assertNull(translated.spoilerText)
        }
    }
}
