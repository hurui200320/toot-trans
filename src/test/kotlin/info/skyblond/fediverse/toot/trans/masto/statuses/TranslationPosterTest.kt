package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.llm.MAX_TOOT_CHARS
import info.skyblond.fediverse.toot.trans.llm.TranslatedToot
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [TranslationPoster]: the segment drafts (chaining, visibility,
 * language, footer, idempotency), the pool seeding and the retry
 * policy.
 * */
@OptIn(ExperimentalCoroutinesApi::class)
class TranslationPosterTest {
    private class Fixture(private val scope: TestScope) {
        val clientFake = TranslationClientFake()
        val pool = StatusesCachePool(
            clientFake.client, scope.backgroundScope,
            batchWindow = 1.milliseconds, timeSource = scope.testScheduler.timeSource,
        )

        /**
         * Virtual clock: reads advance with the test scheduler, so a
         * 429 backoff's `delay` and the reset comparison observe the
         * same time.
         * */
        val clock: Clock = object : Clock() {
            private val epoch = Instant.parse("2026-10-15T00:00:00Z")
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
            override fun instant(): Instant = epoch.plusMillis(scope.testScheduler.currentTime)
        }

        fun poster(
            transientMaxRetry: Int = 2,
            transientRetryDelay: Duration = 1.seconds,
            fallbackDelay: Duration = 5.seconds,
        ): TranslationPoster = TranslationPoster(
            client = clientFake.client,
            pool = pool,
            targetLangCode = "en",
            rateLimitBackoff = RateLimitBackoff(fallbackDelay = fallbackDelay, clock = clock),
            transientMaxRetry = transientMaxRetry,
            transientRetryDelay = transientRetryDelay,
            clock = clock,
        )
    }

    @Test
    fun `a short translation becomes one connected reply carrying the footer`() {
        runTest {
            val f = Fixture(this)
            val original = original("P")

            val created = f.poster().post(original, TranslatedToot("Hello", null))

            assertEquals(listOf("created-1"), created.map { it.id })
            val draft = f.clientFake.createRequests.single()
            assertEquals("Hello\n\n🌐${original.crc32()}#1#1", draft.status)
            // the original's id feeds the idempotency key on every segment
            assertEquals("P", draft.requestId)
            assertEquals("P", draft.inReplyToId)
            assertEquals("en", draft.language)
            assertEquals("unlisted", draft.visibility)
            assertNull(draft.spoilerText)
            assertFalse(draft.sensitive!!)
            // seeded so a later descendant in the same run sees the chain
            assertTrue(f.pool.dump().any { it.status.id == "created-1" })
        }
    }

    @Test
    fun `a long translation chains segments and warns only on the first`() {
        runTest {
            val f = Fixture(this)
            val original = original("P")

            val created = f.poster().post(original, TranslatedToot("a".repeat(461), "Warning"))

            assertEquals(2, created.size)
            val drafts = f.clientFake.createRequests
            assertEquals(listOf("P", "created-1"), drafts.map { it.inReplyToId })
            assertEquals(listOf("en", "en"), drafts.map { it.language })
            // the original's id feeds the idempotency key on every segment
            assertEquals(listOf("P", "P"), drafts.map { it.requestId })
            assertEquals("Warning", drafts[0].spoilerText)
            assertNull(drafts[1].spoilerText)
            assertTrue(drafts[0].status.endsWith("\n\n🌐${original.crc32()}#1#2"))
            assertTrue(drafts[1].status.endsWith("\n\n🌐${original.crc32()}#2#2"))
        }
    }

    @Test
    fun `a body-less translation posts a footer-only reply carrying the warning`() {
        runTest {
            val f = Fixture(this)
            val original = original("P")

            val created = f.poster().post(original, TranslatedToot(content = null, spoilerText = "Warning"))

            assertEquals(listOf("created-1"), created.map { it.id })
            val draft = f.clientFake.createRequests.single()
            // the instance rejects body-less statuses, so the footer alone
            // is the body and the warning carries the translation
            assertEquals("🌐${original.crc32()}#1#1", draft.status)
            assertEquals("Warning", draft.spoilerText)
            assertEquals("P", draft.inReplyToId)
        }
    }

    @Test
    fun `a body-less translation whose warning cannot fit fails fast`() {
        runTest {
            val f = Fixture(this)
            val original = original("P")

            assertFailsWith<IllegalArgumentException> {
                f.poster().post(
                    original,
                    TranslatedToot(content = null, spoilerText = "W".repeat(MAX_TOOT_CHARS)),
                )
            }
            assertTrue(f.clientFake.createRequests.isEmpty())

            // the boundary is the footer's own length: one char less posts
            val fits = MAX_TOOT_CHARS - Footer(original.crc32(), 1, 1).toString().length
            f.poster().post(original, TranslatedToot(content = null, spoilerText = "W".repeat(fits)))
            assertEquals("W".repeat(fits), f.clientFake.createRequests.single().spoilerText)
        }
    }

    @Test
    fun `a translation with neither body nor warning fails fast`() {
        runTest {
            val f = Fixture(this)

            assertFailsWith<IllegalArgumentException> {
                f.poster().post(original("P"), TranslatedToot(content = null, spoilerText = null))
            }

            assertTrue(f.clientFake.createRequests.isEmpty())
        }
    }

    @Test
    fun `public originals become unlisted replies while other visibilities are kept`() {
        runTest {
            val f = Fixture(this)
            val cases = listOf(
                "public" to "unlisted",
                "unlisted" to "unlisted",
                "private" to "private",
                "direct" to "direct",
            )
            for ((originalVisibility, expected) in cases) {
                val original = original("P").copy(visibility = originalVisibility, sensitive = true)
                f.poster().post(original, TranslatedToot("Hello", null))
                val draft = f.clientFake.createRequests.last()
                assertEquals(expected, draft.visibility, originalVisibility)
                assertTrue(draft.sensitive!!, originalVisibility)
            }
        }
    }

    @Test
    fun `a 429 waits out the shared backoff and retries the same segment`() {
        runTest {
            val f = Fixture(this)
            var attempts = 0
            f.clientFake.create = { draft ->
                if (attempts++ == 0) throw MastoApiException(429, "slow down")
                f.clientFake.generatedSegment(draft)
            }

            f.poster().post(original("P"), TranslatedToot("Hello", null))

            assertEquals(2, f.clientFake.createRequests.size)
            assertEquals(5_000, testScheduler.currentTime)
        }
    }

    @Test
    fun `a usable reset header decides the 429 wait`() {
        runTest {
            val f = Fixture(this)
            var attempts = 0
            f.clientFake.create = { draft ->
                if (attempts++ == 0) {
                    throw MastoApiException(
                        429, "slow down",
                        rateLimitReset = Instant.parse("2026-10-15T00:00:02Z").toString(),
                    )
                }
                f.clientFake.generatedSegment(draft)
            }

            f.poster().post(original("P"), TranslatedToot("Hello", null))

            // 2s until the reset plus the default 800ms reset margin
            assertEquals(2_800, testScheduler.currentTime)
        }
    }

    @Test
    fun `rate-limit retries do not consume the transient budget`() {
        runTest {
            val f = Fixture(this)
            var attempts = 0
            f.clientFake.create = { draft ->
                when (attempts++) {
                    0, 1 -> throw MastoApiException(429, "slow down")
                    2 -> throw MastoApiException(503, "overloaded")
                    else -> f.clientFake.generatedSegment(draft)
                }
            }

            // one 5xx retry total: the two 429s must not have spent it
            f.poster(transientMaxRetry = 1).post(original("P"), TranslatedToot("Hello", null))

            assertEquals(4, f.clientFake.createRequests.size)
            // the two fallback backoffs plus the single 5xx retry delay
            assertEquals(5_000 + 5_000 + 1_000, testScheduler.currentTime)
        }
    }

    @Test
    fun `server errors retry within the transient budget`() {
        runTest {
            val f = Fixture(this)
            var attempts = 0
            f.clientFake.create = { draft ->
                if (attempts++ < 2) throw MastoApiException(503, "overloaded")
                f.clientFake.generatedSegment(draft)
            }

            f.poster(transientMaxRetry = 2).post(original("P"), TranslatedToot("Hello", null))

            assertEquals(3, f.clientFake.createRequests.size)
        }
    }

    @Test
    fun `a zero transient budget makes server errors propagate`() {
        runTest {
            val f = Fixture(this)
            f.clientFake.create = { throw MastoApiException(503, "overloaded") }

            assertFailsWith<MastoApiException> {
                f.poster(transientMaxRetry = 0).post(original("P"), TranslatedToot("Hello", null))
            }
            assertEquals(1, f.clientFake.createRequests.size)
        }
    }

    @Test
    fun `transport errors retry within the transient budget`() {
        runTest {
            val f = Fixture(this)
            var attempts = 0
            f.clientFake.create = { draft ->
                if (attempts++ == 0) throw IOException("connection reset")
                f.clientFake.generatedSegment(draft)
            }

            f.poster(transientMaxRetry = 1).post(original("P"), TranslatedToot("Hello", null))

            assertEquals(2, f.clientFake.createRequests.size)
        }
    }

    @Test
    fun `an exhausted transient budget propagates the last transport error`() {
        runTest {
            val f = Fixture(this)
            f.clientFake.create = { throw IOException("connection reset") }

            assertFailsWith<IOException> {
                f.poster(transientMaxRetry = 1).post(original("P"), TranslatedToot("Hello", null))
            }

            // the initial attempt plus the one retry
            assertEquals(2, f.clientFake.createRequests.size)
        }
    }

    @Test
    fun `payload rejections propagate without retrying`() {
        runTest {
            val f = Fixture(this)
            f.clientFake.create = { throw MastoApiException(422, "too long") }

            assertFailsWith<MastoApiException> {
                f.poster().post(original("P"), TranslatedToot("Hello", null))
            }
            assertEquals(1, f.clientFake.createRequests.size)
        }
    }

    @Test
    fun `a partial failure leaves the posted prefix in place`() {
        runTest {
            val f = Fixture(this)
            var attempts = 0
            f.clientFake.create = { draft ->
                if (attempts++ == 0) f.clientFake.generatedSegment(draft)
                else throw MastoApiException(422, "too long")
            }

            assertFailsWith<MastoApiException> {
                f.poster(transientMaxRetry = 0).post(original("P"), TranslatedToot("a".repeat(461), null))
            }
            // the first segment exists; the next run's chain check will
            // report it as invalid and delete it before retranslating
            assertTrue(f.pool.dump().any { it.status.id == "created-1" })
        }
    }
}
