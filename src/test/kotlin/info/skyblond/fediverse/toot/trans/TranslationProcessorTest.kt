package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.llm.FakeChatClient
import info.skyblond.fediverse.toot.trans.llm.MISSING_TRANSLATION_PLACEHOLDER
import info.skyblond.fediverse.toot.trans.llm.Translator
import info.skyblond.fediverse.toot.trans.llm.TranslationRequestFactory
import info.skyblond.fediverse.toot.trans.llm.client.ChatApiException
import info.skyblond.fediverse.toot.trans.llm.client.ChatContent
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionRequest
import info.skyblond.fediverse.toot.trans.llm.completionResponse
import info.skyblond.fediverse.toot.trans.llm.translationAnswer
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.client.media.LoadedMedia
import info.skyblond.fediverse.toot.trans.masto.client.media.MediaLoader
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import info.skyblond.fediverse.toot.trans.masto.statuses.ReplyChainContext
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesDeleter
import info.skyblond.fediverse.toot.trans.masto.statuses.TranslationClientFake
import info.skyblond.fediverse.toot.trans.masto.statuses.fetched
import info.skyblond.fediverse.toot.trans.masto.statuses.now
import info.skyblond.fediverse.toot.trans.masto.statuses.original
import info.skyblond.fediverse.toot.trans.masto.statuses.owner
import info.skyblond.fediverse.toot.trans.masto.statuses.translation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * The queue drain of [TranslationProcessor]: order, skipping, obsolete
 * cleanup, and the failure policy, over real queue/pool/chain/delete
 * components and a fake instance + chat client.
 * */
class TranslationProcessorTest {
    private class Fixture(
        scope: TestScope,
        statuses: List<Status>,
        cooldown: Cooldown = Cooldown(Duration.ZERO),
    ) {
        val clientFake = TranslationClientFake(statuses)
        val pool = StatusesCachePool(
            clientFake.client, scope.backgroundScope,
            batchWindow = 1.milliseconds, timeSource = scope.testScheduler.timeSource,
        )
        val queue = PendingQueue(owner.id, "en", pool)
        val chat = FakeChatClient()
        val context = ReplyChainContext(
            queue, pool, clientFake.client,
            mediaLoader = object : MediaLoader {
                override suspend fun load(url: String): LoadedMedia = error("No media in these tests")
            },
            maxDepth = 3,
        )
        val processor = TranslationProcessor(
            queue = queue,
            cooldown = cooldown,
            deleter = StatusesDeleter(clientFake.client, pool, queue),
            contextBuilder = context,
            translator = Translator(
                chat,
                TranslationRequestFactory("English", "test-model", null, 100, null),
            ),
        )

        suspend fun feed(fetchedAt: Instant = now) {
            queue.feed(clientFake.remote.values.map { fetched(it, fetchedAt) })
        }
    }

    /**
     * The JSON text of the request's last message, i.e. the toot under
     * translation: the tests' scripts use it to tell toots apart.
     * */
    private fun targetText(request: ChatCompletionRequest): String =
        (request.messages.last().content as ChatContent.Text).text

    /** The parsed JSON of one message, for the history assertions. */
    private fun messageJson(request: ChatCompletionRequest, index: Int): String =
        (request.messages[index].content as ChatContent.Text).text

    @Test
    fun `translates the head and drops it`() {
        runTest {
            val f = Fixture(this, listOf(original("P")))
            f.feed()
            f.chat.answerWith(translationAnswer("Hello"))

            f.processor.processQueue()

            assertEquals(1, f.chat.requests.size)
            assertFalse(f.queue.hasNext())
            assertEquals("P", f.queue.cursor())
        }
    }

    @Test
    fun `an already translated chain is dropped without calling the llm`() {
        runTest {
            val p = original("P")
            val f = Fixture(this, listOf(p, translation("T", parent = "P", hash = p.crc32())))
            f.feed()

            f.processor.processQueue()

            assertTrue(f.chat.requests.isEmpty())
            assertFalse(f.queue.hasNext())
        }
    }

    @Test
    fun `a cooling head stops the drain and stays queued`() {
        runTest {
            val f = Fixture(this, listOf(original("P")), cooldown = Cooldown(3.minutes))
            f.feed(fetchedAt = now.minusSeconds(500))

            f.processor.processQueue()

            assertTrue(f.chat.requests.isEmpty())
            assertTrue(f.queue.hasNext())
            assertNull(f.queue.cursor())
        }
    }

    @Test
    fun `a stopped drain resumes on the next call once the head cooled down`() {
        runTest {
            val f = Fixture(this, listOf(original("P")), cooldown = Cooldown(3.minutes))
            f.feed(fetchedAt = now.minusSeconds(500))
            f.processor.processQueue()
            assertTrue(f.chat.requests.isEmpty())
            assertTrue(f.queue.hasNext())

            // the next scan re-observes the toot with a later fetch: the
            // queued copy is replaced and the cooldown has passed
            f.chat.answerWith(translationAnswer("Hello"))
            f.feed()
            f.processor.processQueue()

            assertEquals(1, f.chat.requests.size)
            assertFalse(f.queue.hasNext())
            assertEquals("P", f.queue.cursor())
        }
    }

    @Test
    fun `translation segments at the head are dropped as chain material`() {
        runTest {
            val f = Fixture(this, listOf(translation("T", parent = "P")))
            f.feed()

            f.processor.processQueue()

            assertTrue(f.chat.requests.isEmpty())
            assertFalse(f.queue.hasNext())
        }
    }

    @Test
    fun `obsolete segments are deleted leaf-first before the retranslation`() {
        runTest {
            val p = original("P")
            val t1 = translation("T1", parent = "P", seq = 1, total = 3, hash = p.crc32())
            val t2 = translation("T2", parent = "T1", seq = 2, total = 3, hash = p.crc32())
            val f = Fixture(this, listOf(p, t1, t2))
            f.feed()
            f.chat.answerWith(translationAnswer("Hello"))

            f.processor.processQueue()

            assertEquals(listOf("T2", "T1"), f.clientFake.deleteRequests)
            assertEquals(1, f.chat.requests.size)
            assertFalse(f.queue.hasNext())
        }
    }

    @Test
    fun `a permanent answer failure after obsolete deletion drops the toot`() {
        runTest {
            val p = original("P")
            val t1 = translation("T1", parent = "P", seq = 1, total = 3, hash = p.crc32())
            val t2 = translation("T2", parent = "T1", seq = 2, total = 3, hash = p.crc32())
            val f = Fixture(this, listOf(p, t1, t2))
            f.feed()
            f.chat.answerWith("not a JSON answer")

            f.processor.processQueue()

            // the obsolete segments are gone and the toot is dropped:
            // the next run re-feeds it and retranslates from scratch
            assertEquals(listOf("T2", "T1"), f.clientFake.deleteRequests)
            assertEquals(1, f.chat.requests.size)
            assertFalse(f.queue.hasNext())
            assertEquals("P", f.queue.cursor())
        }
    }

    @Test
    fun `a permanent llm rejection drops the toot and continues`() {
        runTest {
            val q = original("Q")
            val f = Fixture(this, listOf(original("P"), q))
            f.feed()
            f.chat.handler = { request ->
                if (targetText(request).contains("body P")) {
                    throw ChatApiException(400, "rejected")
                }
                completionResponse(translationAnswer("Q translation"))
            }

            f.processor.processQueue()

            assertFalse(f.queue.hasNext())
            assertEquals("Q", f.queue.cursor())
            // P was dropped after the ladder was rejected and has no
            // answer; the drain moved on and Q's request is the last one
            assertEquals(3, f.chat.requests.size)
            assertTrue(targetText(f.chat.requests.last()).contains("body Q"))
        }
    }

    @Test
    fun `an unparseable answer drops the toot and the drain completes`() {
        runTest {
            val f = Fixture(this, listOf(original("P")))
            f.feed()
            f.chat.answerWith("sure, here it is")

            f.processor.processQueue()

            assertEquals(1, f.chat.requests.size)
            assertFalse(f.queue.hasNext())
        }
    }

    @Test
    fun `a malformed llm response stops the drain instead of dropping the toot`() {
        runTest {
            val f = Fixture(this, listOf(original("P")))
            f.feed()
            f.chat.handler = { throw SerializationException("malformed response body") }

            f.processor.processQueue()

            assertEquals(1, f.chat.requests.size)
            assertTrue(f.queue.hasNext())
            assertNull(f.queue.cursor())
        }
    }

    @Test
    fun `a retryable llm failure stops the drain and keeps the entry`() {
        runTest {
            val f = Fixture(this, listOf(original("P")))
            f.feed()
            f.chat.handler = { throw ChatApiException(429, "rate limited") }

            f.processor.processQueue()

            assertTrue(f.queue.hasNext())
            assertNull(f.queue.cursor())
            assertEquals(1, f.chat.requests.size)
        }
    }

    @Test
    fun `credential failures propagate instead of draining every toot`() {
        runTest {
            val f = Fixture(this, listOf(original("P")))
            f.feed()
            f.chat.handler = { throw ChatApiException(401, "bad key") }

            assertFailsWith<ChatApiException> { f.processor.processQueue() }
            assertTrue(f.queue.hasNext())
        }
    }

    @Test
    fun `an instance failure while deleting stops the drain and keeps the entry`() {
        runTest {
            val p = original("P")
            val t1 = translation("T1", parent = "P", seq = 1, total = 3, hash = p.crc32())
            val t2 = translation("T2", parent = "T1", seq = 2, total = 3, hash = p.crc32())
            val f = Fixture(this, listOf(p, t1, t2))
            f.feed()
            f.clientFake.delete = { throw MastoApiException(500, "boom") }

            f.processor.processQueue()

            assertTrue(f.chat.requests.isEmpty())
            assertTrue(f.queue.hasNext())
            assertNull(f.queue.cursor())
        }
    }

    @Test
    fun `a toot deleted outside the app is dropped and the drain continues`() {
        runTest {
            val q = original("Q")
            val f = Fixture(this, listOf(original("P"), q))
            f.feed()
            // P is gone on the instance: reading its source answers 404
            val readSource = f.clientFake.source
            f.clientFake.source = { id ->
                if (id == "P") throw MastoApiException(404, "not found") else readSource(id)
            }
            f.chat.answerWith(translationAnswer("Q translation"))

            f.processor.processQueue()

            // P is dropped for good instead of stopping the drain; Q
            // still reaches the LLM
            assertFalse(f.queue.hasNext())
            assertEquals("Q", f.queue.cursor())
            assertEquals(1, f.chat.requests.size)
            assertTrue(targetText(f.chat.requests.single()).contains("body Q"))
        }
    }

    @Test
    fun `a child's request carries its parent's existing translation`() {
        runTest {
            val p = original("P")
            // the parent's translation sits between the two originals in
            // creation order, so the queue meets it before the child
            val child = original("C", "P").copy(createdAt = now.minusSeconds(120))
            val f = Fixture(this, listOf(p, translation("T", parent = "P", hash = p.crc32()), child))
            f.feed()
            f.chat.answerWith(translationAnswer("Hello C"))

            f.processor.processQueue()

            // only the child reaches the LLM: the parent is already
            // translated and the segment is chain material
            val cRequest = f.chat.requests.single()
            assertEquals(4, cRequest.messages.size)
            val assistant = Json.parseToJsonElement(messageJson(cRequest, 2)).jsonObject
            assertEquals("Translated", assistant["translation"]?.jsonPrimitive?.content)
            assertFalse(messageJson(cRequest, 2).contains(MISSING_TRANSLATION_PLACEHOLDER))
        }
    }
}
