package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StatusesDeleterTest {
    private data class DeleteCall(val id: String, val at: Long)

    private class FakeClient(private val scope: TestScope) : StatusesClient {
        val deleted = mutableListOf<DeleteCall>()
        val fetched = mutableListOf<Set<String>>()
        var fetch: suspend (Set<String>) -> List<Status> = { ids -> ids.map { status(it) } }
        var delete: suspend (String) -> Unit = { }

        override suspend fun getStatuses(statusIds: Set<String>): List<Status> {
            fetched += statusIds.toSet()
            return fetch(statusIds)
        }

        override suspend fun getStatus(statusId: String): Status = error("Must use batch endpoint")
        override suspend fun getStatusSource(statusId: String): StatusSource = error("Not used")
        override suspend fun createStatus(draft: CreateStatusRequest): Status = error("Not used")
        override suspend fun deleteStatus(statusId: String) {
            // record the attempt before applying the fake's failure behavior
            deleted += DeleteCall(statusId, scope.testScheduler.currentTime)
            delete(statusId)
        }
    }

    private class Fixture(private val scope: TestScope) {
        val client = FakeClient(scope)
        private val epoch = Instant.parse("2026-10-15T00:00:00Z")
        private val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
            override fun instant(): Instant = epoch.plusMillis(scope.testScheduler.currentTime)
        }

        /**
         * The 429 backoff the deleter runs on, over the virtual clock;
         * settings default to the built-in ones so tests can override
         * just what they pin.
         * */
        fun backoff(
            fallbackDelay: Duration = RateLimitBackoff.DEFAULT_FALLBACK_DELAY,
            maxWaitDuration: Duration = RateLimitBackoff.DEFAULT_MAX_WAIT_DURATION,
        ) = RateLimitBackoff(
            fallbackDelay = fallbackDelay,
            maxWaitDuration = maxWaitDuration,
            clock = clock,
        )

        fun pool() = StatusesPool(
            client, scope.backgroundScope,
            rateLimitBackoff = backoff(),
            timeSource = scope.testScheduler.timeSource,
        )

        fun deleter(
            pool: StatusesPool,
            fallbackDelay: Duration = RateLimitBackoff.DEFAULT_FALLBACK_DELAY,
        ) = StatusesDeleter(client, pool, rateLimitBackoff = backoff(fallbackDelay = fallbackDelay))
    }

    companion object {
        private fun status(id: String) = Status(
            id = id, createdAt = Instant.parse("2026-10-15T00:00:00Z"), content = id,
            visibility = "public", sensitive = false, mediaAttachments = emptyList(),
        )
    }

    @Test
    fun `deletes in the given order and invalidates each deleted status`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        val segments = listOf(status("3"), status("2"), status("1"))
        pool.seed(segments)
        // the instance no longer holds the deleted statuses
        f.client.fetch = { emptyList() }

        f.deleter(pool).delete(segments)

        assertEquals(listOf("3", "2", "1"), f.client.deleted.map { it.id })
        // the cache entries are gone: a lookup reaches the instance and finds nothing
        val lookup = async { pool.getStatus("2") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(lookup.await())
        assertEquals(listOf(setOf("2")), f.client.fetched)
    }

    @Test
    fun `429 waits out the reset then retries the same status`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        f.client.delete = { id ->
            if (id == "2" && f.client.deleted.count { it.id == "2" } == 1) {
                throw MastoApiException(429, "limited", "2026-10-15T00:00:05Z")
            }
        }

        val done = async { f.deleter(pool).delete(listOf(status("3"), status("2"), status("1"))) }
        runCurrent()
        // "3" is deleted, then "2" hits the 429 at t=0
        assertEquals(listOf("3", "2"), f.client.deleted.map { it.id })

        // the reset is 5s out, plus the 800ms margin: nothing retries before then
        advanceTimeBy(5_799)
        runCurrent()
        assertEquals(listOf("3", "2"), f.client.deleted.map { it.id })

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("3", "2", "2", "1"), f.client.deleted.map { it.id })
        assertEquals(listOf(0L, 0L, 5_800L, 5_800L), f.client.deleted.map { it.at })
        done.await()
        assertTrue(done.isCompleted)
    }

    @Test
    fun `unusable reset header falls back to the fixed delay`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        f.client.delete = { id ->
            if (f.client.deleted.count { it.id == id } == 1) {
                throw MastoApiException(429, "limited", "not-a-date")
            }
        }

        val done = async { f.deleter(pool, fallbackDelay = 2.seconds).delete(listOf(status("1"))) }
        runCurrent()
        assertEquals(listOf(0L), f.client.deleted.map { it.at })

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(listOf(0L), f.client.deleted.map { it.at })

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0L, 2_000L), f.client.deleted.map { it.at })
        done.await()
        assertTrue(done.isCompleted)
    }

    @Test
    fun `unexpected failures stop the chain and leave remaining cache entries intact`() = runTest {
        for (error in listOf(MastoApiException(500, "boom"), IOException("offline"))) {
            val f = Fixture(this)
            val pool = f.pool()
            pool.seed(listOf(status("3"), status("2"), status("1")))
            f.client.delete = { id -> if (id == "2") throw error }

            val failure = assertFails {
                f.deleter(pool).delete(listOf(status("3"), status("2"), status("1")))
            }

            assertEquals(error::class, failure::class)
            assertEquals(error.message, failure.message)
            // the chain stops at "2": "1" is never attempted
            assertEquals(listOf("3", "2"), f.client.deleted.map { it.id })
            // "2" and "1" are still cached; "3" was invalidated after its successful deletion
            assertEquals(status("2"), pool.getStatus("2"))
            assertEquals(status("1"), pool.getStatus("1"))
            f.client.fetch = { emptyList() }
            val lookup = async { pool.getStatus("3") }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertNull(lookup.await())
            assertEquals(listOf(setOf("3")), f.client.fetched)
        }
    }

    @Test
    fun `404 counts as already gone and continues the chain`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        pool.seed(listOf(status("3"), status("2"), status("1")))
        f.client.delete = { id ->
            if (id == "2") throw MastoApiException(404, "gone")
        }

        f.deleter(pool).delete(listOf(status("3"), status("2"), status("1")))

        // "2" is not retried and "1" is still deleted
        assertEquals(listOf("3", "2", "1"), f.client.deleted.map { it.id })
        // the already-gone status is dropped from the cache like a deleted one
        f.client.fetch = { emptyList() }
        val lookups = listOf("3", "2", "1").map { async { pool.getStatus(it) } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(null, null, null), lookups.map { it.await() })
        assertEquals(listOf(setOf("3", "2", "1")), f.client.fetched)
    }

    @Test
    fun `cancellation during backoff propagates and stops the chain`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        f.client.delete = { id ->
            if (id == "1") throw MastoApiException(429, "limited", "2026-10-15T00:01:00Z")
        }

        val done = async { f.deleter(pool).delete(listOf(status("1"), status("2"))) }
        runCurrent()
        assertEquals(listOf("1"), f.client.deleted.map { it.id })

        done.cancel()
        runCurrent()
        assertFailsWith<CancellationException> { done.await() }

        advanceTimeBy(61_000)
        runCurrent()
        // cancelled: no retry, and "2" is never attempted
        assertEquals(listOf("1"), f.client.deleted.map { it.id })
    }

    @Test
    fun `cancellation after a successful delete still invalidates the cache`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        pool.seed(listOf(status("1")))
        // the fake cancels the caller from inside the DELETE: the pending
        // cancellation surfaces at the next suspension point, which is the
        // invalidation itself
        val done = async(start = CoroutineStart.LAZY) {
            f.deleter(pool).delete(listOf(status("1")))
        }
        f.client.delete = { done.cancel() }
        done.start()
        runCurrent()

        assertFailsWith<CancellationException> { done.await() }
        assertEquals(listOf("1"), f.client.deleted.map { it.id })
        // the DELETE had succeeded, so the cache entry must be gone: the
        // lookup reaches the instance instead of serving the stale seed
        f.client.fetch = { emptyList() }
        val lookup = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(lookup.await())
        assertEquals(listOf(setOf("1")), f.client.fetched)
    }

    @Test
    fun `blank ids fail fast before any deletion and empty input is a no-op`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        val deleter = f.deleter(pool)

        assertFailsWith<IllegalArgumentException> {
            deleter.delete(listOf(status("1"), status("")))
        }
        deleter.delete(emptyList())

        assertTrue(f.client.deleted.isEmpty())
    }
}
