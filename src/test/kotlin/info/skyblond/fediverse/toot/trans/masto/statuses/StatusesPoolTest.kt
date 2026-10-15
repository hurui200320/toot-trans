package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StatusesPoolTest {
    private data class Call(val ids: Set<String>, val at: Long)

    private class FakeClient(private val scope: TestScope) : StatusesClient {
        val calls = mutableListOf<Call>()
        var fetch: suspend (Set<String>) -> List<Status> = { ids -> ids.map { status(it) } }

        override suspend fun getStatuses(statusIds: Set<String>): List<Status> {
            calls += Call(statusIds.toSet(), scope.testScheduler.currentTime)
            return fetch(statusIds)
        }

        override suspend fun getStatus(statusId: String): Status = error("Must use batch endpoint")
        override suspend fun getStatusSource(statusId: String): StatusSource = error("Not used")
        override suspend fun createStatus(draft: CreateStatusRequest): Status = error("Not used")
        override suspend fun deleteStatus(statusId: String) = error("Not used")
    }

    private class Fixture(val scope: TestScope) {
        val client = FakeClient(scope)
        private val epoch = Instant.parse("2026-10-15T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
            override fun instant(): Instant = epoch.plusMillis(scope.testScheduler.currentTime)
        }

        /**
         * The backoff the pool's 429 handling runs on, over the virtual
         * clock; settings default to the built-in ones so tests can
         * override just what they pin.
         * */
        fun backoff(
            fallbackDelay: Duration = RateLimitBackoff.DEFAULT_FALLBACK_DELAY,
            maxWaitDuration: Duration = RateLimitBackoff.DEFAULT_MAX_WAIT_DURATION,
        ) = RateLimitBackoff(
            fallbackDelay = fallbackDelay,
            maxWaitDuration = maxWaitDuration,
            clock = clock,
        )

        fun pool(maxCacheEntries: Int = 1_000) = StatusesPool(
            client, scope.backgroundScope,
            maxCacheEntries = maxCacheEntries,
            rateLimitBackoff = backoff(),
            timeSource = scope.testScheduler.timeSource,
        )
    }

    companion object {
        private fun status(id: String, content: String = id) = Status(
            id = id, createdAt = Instant.parse("2026-10-15T00:00:00Z"), content = content,
            visibility = "public", sensitive = false, mediaAttachments = emptyList(),
        )
    }

    @Test
    fun `misses batch with a fixed window and deduplicate IDs`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        val first = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(900)
        val duplicate = async { pool.getStatus("1") }
        val second = async { pool.getStatus("2") }
        runCurrent()
        assertTrue(f.client.calls.isEmpty())
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(Call(setOf("1", "2"), 1_000)), f.client.calls)
        assertEquals(status("1"), first.await())
        assertEquals(first.await(), duplicate.await())
        assertEquals(status("2"), second.await())
    }

    @Test
    fun `overflow batches are FIFO and fire back to back once collected`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        val results = (1..45).map { async { pool.getStatus(it.toString()) } }
        runCurrent()
        // nothing fires before the batch window elapses
        assertTrue(f.client.calls.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        // overflow past MAX_IDS_PER_CALL no longer waits between batches
        assertEquals(3, f.client.calls.size)
        assertEquals((1..45).map { it.toString() }, f.client.calls.flatMap { it.ids })
        assertEquals(List(3) { 1_000L }, f.client.calls.map { it.at })
        assertEquals(listOf(20, 20, 5), f.client.calls.map { it.ids.size })
        assertEquals((1..45).map { status(it.toString()) }, results.map { it.await() })
    }

    @Test
    fun `late requests join an in flight lookup without concurrent HTTP calls`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.client.fetch = { ids -> gate.await(); ids.map { status(it) } }
        val pool = f.pool()
        val first = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val duplicate = async { pool.getStatus("1", bypassCache = true) }
        val other = async { pool.getStatus("2") }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, f.client.calls.size)
        gate.complete(Unit)
        runCurrent()
        assertEquals(status("1"), first.await())
        assertEquals(first.await(), duplicate.await())
        assertEquals(status("2"), other.await())
        assertEquals(listOf(1_000L, 6_000L), f.client.calls.map { it.at })
    }

    @Test
    fun `cache expires after insertion even with repeated reads`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        pool.seed(listOf(status("1")))
        advanceTimeBy(30_000)
        assertEquals(status("1"), pool.getStatus("1"))
        advanceTimeBy(29_999)
        assertEquals(status("1"), pool.getStatus("1"))
        advanceTimeBy(1)
        val refresh = async { pool.getStatus("1") }
        runCurrent()
        assertFalse(refresh.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1"), refresh.await())
        assertEquals(1, f.client.calls.size)
    }

    @Test
    fun `LRU evicts least recently read entries`() = runTest {
        val f = Fixture(this)
        val pool = f.pool(maxCacheEntries = 2)
        pool.seed(listOf(status("1"), status("2")))
        assertEquals(status("1"), pool.getStatus("1"))
        pool.seed(listOf(status("3")))
        assertEquals(status("1"), pool.getStatus("1"))
        assertEquals(status("3"), pool.getStatus("3"))
        val evicted = async { pool.getStatus("2") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("2"), evicted.await())
        assertEquals(setOf("2"), f.client.calls.single().ids)
    }

    @Test
    fun `missing and unordered results resolve by ID without caching missing statuses`() = runTest {
        val f = Fixture(this)
        f.client.fetch = { listOf(status("3"), status("1")) }
        val pool = f.pool()
        val results = (1..3).map { async { pool.getStatus(it.toString()) } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(status("1"), null, status("3")), results.map { it.await() })
        val missing = async { pool.getStatus("2") }
        runCurrent()
        assertFalse(missing.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(missing.await())
        assertEquals(2, f.client.calls.size)
    }

    @Test
    fun `bypass skips cache and replaces stored values`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        pool.seed(listOf(status("1", "old")))
        f.client.fetch = { listOf(status("1", "edited")) }
        val forced = async { pool.getStatus("1", bypassCache = true) }
        val missing = async { pool.getStatus("2") }
        runCurrent()
        assertEquals(status("1", "old"), pool.getStatus("1"))
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1", "edited"), forced.await())
        assertNull(missing.await())
        assertEquals(forced.await(), pool.getStatus("1"))
        f.client.fetch = { ids -> ids.map { status(it) } }
        val previouslyMissing = async { pool.getStatus("2") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("2"), previouslyMissing.await())
        assertEquals(listOf(1_000L, 2_000L), f.client.calls.map { it.at })
    }

    @Test
    fun `missing forced fetch removes stale cache without evicting other statuses`() = runTest {
        val f = Fixture(this)
        val pool = f.pool(maxCacheEntries = 2)
        pool.seed(listOf(status("1"), status("2")))
        f.client.fetch = { emptyList() }
        val forced = async { pool.getStatus("1", bypassCache = true) }
        val missing = async { pool.getStatus("3") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertNull(forced.await())
        assertNull(missing.await())
        assertEquals(status("2"), pool.getStatus("2"))
        f.client.fetch = { ids -> ids.map { status(it, "new") } }
        val fresh = async { pool.getStatus("1") }
        runCurrent()
        assertFalse(fresh.isCompleted)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1", "new"), fresh.await())
        assertEquals(listOf(setOf("1", "3"), setOf("1")), f.client.calls.map { it.ids })
    }

    @Test
    fun `429 pauses all misses and bypass requests while cache hits remain available`() = runTest {
        val f = Fixture(this)
        f.client.fetch = { ids ->
            if (f.client.calls.size == 1) throw MastoApiException(429, "limited", "2026-10-15T00:00:10Z")
            ids.map { status(it) }
        }
        val pool = f.pool()
        pool.seed(listOf(status("cached")))
        val first = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val forced = async { pool.getStatus("cached", bypassCache = true) }
        val second = async { pool.getStatus("2") }
        runCurrent()
        assertEquals(status("cached"), pool.getStatus("cached"))
        advanceTimeBy(9_099)
        runCurrent()
        assertEquals(1, f.client.calls.size)
        assertFalse(first.isCompleted)
        assertFalse(forced.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(status("1"), first.await())
        assertEquals(10_800L, f.client.calls[1].at)
        runCurrent()
        assertEquals(status("cached"), forced.await())
        assertEquals(status("2"), second.await())
        // queued misses fire immediately after the backoff retry, no pacing
        assertEquals(10_800L, f.client.calls[2].at)
    }

    @Test
    fun `unusable reset headers use fixed fallback`() = runTest {
        for (header in listOf(null, "invalid", "2026-10-14T00:00:00Z", "9999-12-31T00:00:00Z")) {
            val f = Fixture(this)
            f.client.fetch = { ids ->
                if (f.client.calls.size <= 4) throw MastoApiException(429, "limited", header)
                ids.map { status(it) }
            }
            val pool = StatusesPool(
                f.client, backgroundScope,
                rateLimitBackoff = f.backoff(fallbackDelay = 2.seconds),
                timeSource = testScheduler.timeSource,
            )
            val start = testScheduler.currentTime
            val result = async { pool.getStatus("1") }
            runCurrent()
            advanceTimeBy(9_000)
            runCurrent()
            assertEquals(status("1"), result.await())
            assertEquals(listOf(1_000L, 3_000L, 5_000L, 7_000L, 9_000L), f.client.calls.map { it.at - start })
            withContext(Dispatchers.IO) { pool.close() }
        }
    }

    @Test
    fun `far future but usable reset is honored`() = runTest {
        val f = Fixture(this)
        f.client.fetch = { ids ->
            if (f.client.calls.size == 1) throw MastoApiException(429, "limited", "2026-10-15T00:25:00Z")
            ids.map { status(it) }
        }
        val pool = f.pool()
        val result = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1"), result.await())
        // 25:00:00 reset minus 00:00:01 now, plus the 800ms margin
        assertEquals(listOf(1_000L, 1_500_800L), f.client.calls.map { it.at })
    }

    @Test
    fun `customized long reset windows within maxWaitDuration are honored`() = runTest {
        val f = Fixture(this)
        f.client.fetch = { ids ->
            if (f.client.calls.size == 1) throw MastoApiException(429, "limited", "2026-10-15T01:00:00Z")
            ids.map { status(it) }
        }
        val pool = f.pool()
        val result = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1"), result.await())
        // one-hour reset minus the call time, plus the 800ms margin
        assertEquals(listOf(1_000L, 3_600_800L), f.client.calls.map { it.at })
    }

    @Test
    fun `reset windows beyond maxWaitDuration use the fallback delay`() = runTest {
        val f = Fixture(this)
        f.client.fetch = { ids ->
            if (f.client.calls.size == 1) throw MastoApiException(429, "limited", "2026-10-15T00:25:00Z")
            ids.map { status(it) }
        }
        val pool = StatusesPool(
            f.client, backgroundScope,
            rateLimitBackoff = f.backoff(fallbackDelay = 2.seconds, maxWaitDuration = 5.minutes),
            timeSource = testScheduler.timeSource,
        )
        val result = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1"), result.await())
        assertEquals(listOf(1_000L, 3_000L), f.client.calls.map { it.at })
    }

    @Test
    fun `non rate limit errors fail the batch without being cached or killing worker`() = runTest {
        for (error in listOf(MastoApiException(401, "unauthorized"), IOException("offline"))) {
            val f = Fixture(this)
            f.client.fetch = { throw error }
            val pool = f.pool()
            // Catch inside the children so expected errors do not cancel the test scope.
            val failures = (1..2).map { async { runCatching { pool.getStatus(it.toString()) }.exceptionOrNull() } }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            // Coroutine stack-trace recovery can copy an IOException.
            failures.forEach {
                val failure = it.await()
                assertEquals(error::class, failure!!::class)
                assertEquals(error.message, failure.message)
            }
            f.client.fetch = { ids -> ids.map { status(it) } }
            val retry = async { pool.getStatus("1") }
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(status("1"), retry.await())
            assertEquals(2, f.client.calls.size)
            withContext(Dispatchers.IO) { pool.close() }
        }
    }

    @Test
    fun `cancelling one caller does not cancel shared in flight work`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.client.fetch = { ids -> gate.await(); ids.map { status(it) } }
        val pool = f.pool()
        val first = async { pool.getStatus("1") }
        val second = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        first.cancel()
        runCurrent()
        assertFalse(second.isCompleted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(status("1"), second.await())
        assertEquals(1, f.client.calls.size)
    }

    @Test
    fun `seed resolves in flight and queued lookups without stale responses overwriting it`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.client.fetch = { ids -> gate.await(); ids.map { status(it, "old") } }
        val pool = f.pool()
        val inFlight = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        val queued = async { pool.getStatus("2") }
        runCurrent()
        pool.seed(listOf(status("1", "new"), status("2", "new")))
        assertEquals(status("1", "new"), inFlight.await())
        assertEquals(status("2", "new"), queued.await())
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(status("1", "new"), pool.getStatus("1"))
        assertEquals(1, f.client.calls.size)
    }

    @Test
    fun `invalidate prevents stale in flight fetch from repopulating cache`() = runTest {
        val f = Fixture(this)
        val gate = CompletableDeferred<Unit>()
        f.client.fetch = { ids ->
            if (f.client.calls.size == 1) gate.await()
            ids.map { status(it, if (f.client.calls.size == 1) "old" else "new") }
        }
        val pool = f.pool()
        val old = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        pool.invalidate("1")
        assertFailsWith<CancellationException> { old.await() }
        val fresh = async { pool.getStatus("1") }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(status("1", "new"), fresh.await())
        assertEquals(fresh.await(), pool.getStatus("1"))
    }

    @Test
    fun `close releases queued in flight and rate limited callers and rejects new work`() = runTest {
        for (mode in listOf("queued", "inFlight", "limited")) {
            val f = Fixture(this)
            f.client.fetch = {
                // a reset within resetWait's sanity cap, so this mode
                // stays on the reset-based backoff path
                if (mode == "limited") throw MastoApiException(429, "limited", "2026-10-15T00:20:00Z")
                CompletableDeferred<List<Status>>().await()
            }
            val pool = f.pool()
            val result = async { pool.getStatus("1") }
            runCurrent()
            if (mode != "queued") {
                advanceTimeBy(1_000)
                runCurrent()
            }
            withContext(Dispatchers.IO) { pool.close() }
            assertFailsWith<CancellationException> { result.await() }
            assertFailsWith<CancellationException> { pool.getStatus("2") }
            assertFailsWith<CancellationException> { pool.seed(listOf(status("2"))) }
            assertFailsWith<CancellationException> { pool.invalidate("2") }
        }
    }

    @Test
    fun `immediate close releases queued lookups before returning`() = runTest {
        val f = Fixture(this)
        val parent = Job()
        val pool = StatusesPool(
            f.client, CoroutineScope(parent + Dispatchers.Default),
            rateLimitBackoff = f.backoff(), timeSource = testScheduler.timeSource,
        )
        val result = async(start = CoroutineStart.UNDISPATCHED) { pool.getStatus("1") }
        try {
            assertFalse(result.isCompleted)
            pool.close()
            runCurrent()
            assertTrue(result.isCompleted, "Close must release the queued caller")
            assertFailsWith<CancellationException> { result.await() }
            assertFailsWith<CancellationException> { pool.getStatus("2") }
            assertTrue(f.client.calls.isEmpty())
        } finally {
            pool.close()
            result.cancel()
            parent.cancel()
        }
    }

    @Test
    fun `close waits for in flight cleanup and leaves the supplied scope active`() = runTest {
        val f = Fixture(this)
        val cleanupStarted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var cleanedUp = false
        f.client.fetch = {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    finishCleanup.await()
                    cleanedUp = true
                }
            }
        }
        val parent = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + parent)
        val pool = StatusesPool(f.client, scope, rateLimitBackoff = f.backoff(), timeSource = testScheduler.timeSource)
        val result = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        // Blocking close must run off the test scheduler's thread so cleanup can progress.
        val closing = async(Dispatchers.IO) { pool.close() }
        try {
            cleanupStarted.await()
            assertFalse(closing.isCompleted)
            assertFalse(cleanedUp)
            finishCleanup.complete(Unit)
            closing.await()
            assertTrue(cleanedUp)
            assertTrue(parent.isActive)
            assertFailsWith<CancellationException> { result.await() }
            assertFailsWith<CancellationException> { pool.getStatus("2") }
            withContext(Dispatchers.IO) { pool.close() }
            assertTrue(parent.isActive)
        } finally {
            finishCleanup.complete(Unit)
            closing.await()
            result.cancel()
            parent.cancel()
        }
    }

    @Test
    fun `pending queue accepts more than one thousand distinct misses and preserves FIFO and deduplication`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        pool.seed(listOf(status("cached")))
        val results = (1..1_005).map { async { pool.getStatus(it.toString()) } }
        val duplicate = async { pool.getStatus("1", bypassCache = true) }
        runCurrent()
        assertEquals(status("cached"), pool.getStatus("cached"))
        assertTrue(f.client.calls.isEmpty())
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals((1..1_005).map { status(it.toString()) }, results.map { it.await() })
        assertEquals(results.first().await(), duplicate.await())
        assertEquals((1..1_005).map { it.toString() }, f.client.calls.flatMap { it.ids })
        // all 51 batches fire as soon as the batch window elapses
        assertEquals(List(51) { 1_000L }, f.client.calls.map { it.at })
        assertTrue(f.client.calls.all { it.ids.size <= StatusesClient.MAX_IDS_PER_CALL })
    }

    @Test
    fun `blank IDs and invalid settings fail fast`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        assertFailsWith<IllegalArgumentException> { pool.getStatus(" ") }
        assertFailsWith<IllegalArgumentException> { pool.invalidate("") }
        assertFailsWith<IllegalArgumentException> { pool.seed(listOf(status(""))) }
        val batchWindow = assertFailsWith<IllegalArgumentException> {
            StatusesPool(f.client, backgroundScope, batchWindow = 0.milliseconds)
        }
        assertTrue(batchWindow.message!!.contains("batchWindow"))
        val cacheTtl = assertFailsWith<IllegalArgumentException> {
            StatusesPool(f.client, backgroundScope, cacheTtl = 0.milliseconds)
        }
        assertTrue(cacheTtl.message!!.contains("cacheTtl"))
        val resetMargin = assertFailsWith<IllegalArgumentException> {
            StatusesPool(f.client, backgroundScope, rateLimitBackoff = RateLimitBackoff(resetMargin = (-1).milliseconds))
        }
        assertTrue(resetMargin.message!!.contains("resetMargin"))
        val fallbackDelay = assertFailsWith<IllegalArgumentException> {
            StatusesPool(f.client, backgroundScope, rateLimitBackoff = RateLimitBackoff(fallbackDelay = 0.milliseconds))
        }
        assertTrue(fallbackDelay.message!!.contains("fallbackDelay"))
        val maxWaitDuration = assertFailsWith<IllegalArgumentException> {
            StatusesPool(f.client, backgroundScope, rateLimitBackoff = RateLimitBackoff(maxWaitDuration = 0.seconds))
        }
        assertTrue(maxWaitDuration.message!!.contains("maxWaitDuration"))
        val maxCacheEntries = assertFailsWith<IllegalArgumentException> {
            StatusesPool(f.client, backgroundScope, maxCacheEntries = 0)
        }
        assertTrue(maxCacheEntries.message!!.contains("maxCacheEntries"))
        assertTrue(f.client.calls.isEmpty())
    }

    @Test
    fun `idle periods do not trigger polling or catch up bursts`() = runTest {
        val f = Fixture(this)
        val pool = f.pool()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(f.client.calls.isEmpty())
        val first = async { pool.getStatus("1") }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(f.client.calls.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(status("1"), first.await())
        assertEquals(61_000L, f.client.calls.single().at)
    }

    @Test
    fun `parent scope cancellation releases outstanding callers`() = runTest {
        val f = Fixture(this)
        val parent = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + parent)
        val pool = StatusesPool(f.client, scope, rateLimitBackoff = f.backoff(), timeSource = testScheduler.timeSource)
        val result = async { pool.getStatus("1") }
        runCurrent()
        parent.cancel()
        runCurrent()
        assertFailsWith<CancellationException> { result.await() }
        assertFailsWith<CancellationException> { pool.getStatus("2") }
        assertTrue(f.client.calls.isEmpty())
    }

    @Test
    fun `immediate parent cancellation releases callers before worker dispatcher runs`() = runTest {
        val f = Fixture(this)
        val parent = Job(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + parent)
        val pool = StatusesPool(f.client, scope, rateLimitBackoff = f.backoff(), timeSource = testScheduler.timeSource)
        val result = async(start = CoroutineStart.UNDISPATCHED) { pool.getStatus("1") }
        try {
            assertFalse(result.isCompleted)
            parent.cancel()
            runCurrent()
            assertTrue(result.isCompleted, "Parent cancellation must release the queued caller")
            assertFailsWith<CancellationException> { result.await() }
            assertFailsWith<CancellationException> { pool.getStatus("2") }
            assertTrue(f.client.calls.isEmpty())
        } finally {
            result.cancel()
            parent.cancel()
        }
    }
}
