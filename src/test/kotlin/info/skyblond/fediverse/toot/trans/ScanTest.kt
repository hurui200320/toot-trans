package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.statuses.CreateStatusRequest
import info.skyblond.fediverse.toot.trans.masto.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ScanTest {
    private val account = CredentialAccount(id = "42", username = "skyblond")

    /**
     * One recorded `/api/v1/accounts/:id/statuses` call; [at] is the
     * virtual time it fired, pinning retry waits.
     * */
    private data class GetStatusesCall(
        val accountId: String,
        val limit: Int,
        val maxId: String?,
        val excludeReblogs: Boolean,
        val at: Long,
    )

    /**
     * Serves [statuses] (stored newest first, like the endpoint lists
     * them) with `max_id` pagination: toots with ids below `maxId`,
     * newest first, up to `limit`. Ids are numeric strings, compared
     * as numbers. [failure] scripts an exception by call index
     * (0-based, failed attempts included); null lets the call succeed.
     * */
    private class FakeAccountsClient(
        private val statuses: List<Status>,
        private val scope: TestScope,
    ) : AccountsClient {
        val calls = mutableListOf<GetStatusesCall>()
        var failure: (Int) -> Exception? = { null }

        override suspend fun verifyCredentials(): CredentialAccount = error("Not used")

        override suspend fun getStatuses(
            accountId: String,
            limit: Int,
            sinceId: String?,
            minId: String?,
            maxId: String?,
            excludeReblogs: Boolean,
        ): List<Status> {
            calls += GetStatusesCall(
                accountId, limit, maxId, excludeReblogs, scope.testScheduler.currentTime
            )
            failure(calls.size - 1)?.let { throw it }
            return statuses
                .filter { maxId == null || it.id.toInt() < maxId.toInt() }
                .take(limit)
        }
    }

    /**
     * Serves [known] by id for the pool's batch lookups, recording calls.
     * */
    private class FakeStatusesClient(
        private val known: Map<String, Status>,
    ) : StatusesClient {
        val calls = mutableListOf<Set<String>>()

        override suspend fun getStatuses(statusIds: Set<String>): List<Status> {
            calls += statusIds
            return statusIds.mapNotNull(known::get)
        }

        override suspend fun getStatus(statusId: String): Status = error("Must use the batch endpoint")
        override suspend fun getStatusSource(statusId: String): StatusSource = error("Not used")
        override suspend fun createStatus(draft: CreateStatusRequest): Status = error("Not used")
        override suspend fun deleteStatus(statusId: String) = error("Not used")
    }

    private fun TestScope.poolFor(
        client: StatusesClient,
        maxCacheEntries: Int = 1_000,
    ): StatusesCachePool = StatusesCachePool(
        client, backgroundScope,
        maxCacheEntries = maxCacheEntries,
        timeSource = testScheduler.timeSource,
    )

    /**
     * A [Clock] reading the scheduler's virtual time, so a reset
     * timestamp like [epoch] plus N seconds waits exactly N virtual
     * seconds.
     * */
    private fun TestScope.testClock(): Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        override fun instant(): Instant = epoch.plusMillis(testScheduler.currentTime)
    }

    companion object {
        private val epoch = Instant.parse("2026-10-15T00:00:00Z")

        /**
         * Toot created [id] seconds after [epoch], so id order equals
         * creation-time order.
         * */
        private fun status(id: Int, editedAt: Instant? = null) = Status(
            id = id.toString(),
            createdAt = epoch.plusSeconds(id.toLong()),
            editedAt = editedAt,
            content = "content-$id",
            visibility = "public",
            sensitive = false,
            mediaAttachments = emptyList(),
        )
    }

    @Test
    fun `paginates with maxId, seeds the pool, and returns old to new`() = runTest {
        // 50 toots, id 50 the newest; the lookup fake knows them all
        // so a cache miss would be visible as a worker call
        val statuses = (50 downTo 1).map { status(it) }
        val accounts = FakeAccountsClient(statuses, this)
        val lookup = FakeStatusesClient(statuses.associateBy { it.id })
        val pool = poolFor(lookup)

        val result = performInitialScan(accounts, pool, account, size = 50)

        // a full page, then the remaining 10 under the first page's
        // oldest id; boosts always filtered server-side
        assertEquals(
            listOf(
                GetStatusesCall("42", 40, null, true, 0),
                GetStatusesCall("42", 10, "11", true, 0),
            ),
            accounts.calls,
        )

        // old to new, which equals id order here
        assertEquals((1..50).map { it.toString() }, result.map { it.id })

        // everything answers from the cache; advancing past the batch
        // window proves no lookup was even queued
        assertEquals(result, result.map { pool.getStatus(it.id) })
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, lookup.calls.size)
    }

    @Test
    fun `a size below the page limit fetches a single clamped call`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(accounts, pool, account, size = 5)

        // the request asks for only what is still needed
        assertEquals(listOf(GetStatusesCall("42", 5, null, true, 0)), accounts.calls)
        assertEquals((46..50).map { it.toString() }, result.map { it.id })
    }

    @Test
    fun `size zero skips the scan entirely`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)
        val lookup = FakeStatusesClient(emptyMap())
        val pool = poolFor(lookup)

        val result = performInitialScan(accounts, pool, account, size = 0)

        assertEquals(0, result.size)
        assertTrue(accounts.calls.isEmpty())
        assertTrue(lookup.calls.isEmpty())
    }

    @Test
    fun `negative size fails fast before any call`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<IllegalArgumentException> {
            performInitialScan(accounts, pool, account, size = -1)
        }

        assertTrue(e.message!!.contains("size"))
        assertTrue(accounts.calls.isEmpty())
    }

    @Test
    fun `a non-positive page limit fails fast before any call`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        for (limit in listOf(0, -1)) {
            val e = assertFailsWith<IllegalArgumentException> {
                performInitialScan(accounts, pool, account, size = 1, limit = limit)
            }

            assertTrue(e.message!!.contains("limit"))
        }
        assertTrue(accounts.calls.isEmpty())
    }

    @Test
    fun `stops early when the account has fewer toots than requested`() = runTest {
        // only 5 toots exist, a full page is requested
        val accounts = FakeAccountsClient((5 downTo 1).map { status(it) }, this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(accounts, pool, account, size = 40)

        // the short page means no second request
        assertEquals(listOf(GetStatusesCall("42", 40, null, true, 0)), accounts.calls)
        assertEquals((1..5).map { it.toString() }, result.map { it.id })
    }

    @Test
    fun `an account without toots yields an empty scan`() = runTest {
        val accounts = FakeAccountsClient(emptyList(), this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(accounts, pool, account, size = 40)

        assertEquals(0, result.size)
        assertEquals(listOf(GetStatusesCall("42", 40, null, true, 0)), accounts.calls)
    }

    @Test
    fun `an edit moves a toot to its edit time in the result order`() = runTest {
        // toot 1 is the oldest but was edited after toots 2 and 3 were
        // posted; the endpoint still lists by creation, newest first
        val accounts = FakeAccountsClient(
            listOf(status(3), status(2), status(1, editedAt = epoch.plusSeconds(10))), this
        )
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(accounts, pool, account, size = 3)

        // by editedAt ?: createdAt: 2 (t+2), 3 (t+3), 1 (edited t+10)
        assertEquals(listOf("2", "3", "1"), result.map { it.id })
    }

    @Test
    fun `seeding order keeps the oldest toots cached under eviction`() = runTest {
        // two pages of two, and a pool holding only two entries; the
        // second (older) page must push the first page's entries out
        val statuses = (4 downTo 1).map { status(it) }
        val accounts = FakeAccountsClient(statuses, this)
        val lookup = FakeStatusesClient(statuses.associateBy { it.id })
        val pool = poolFor(lookup, maxCacheEntries = 2)

        val result = performInitialScan(accounts, pool, account, size = 4, limit = 2)

        assertEquals((1..4).map { it.toString() }, result.map { it.id })

        // the oldest two — processed first — survived the eviction and
        // answer from the cache
        assertEquals(result[0], pool.getStatus(result[0].id))
        assertEquals(result[1], pool.getStatus(result[1].id))

        // the newest two were evicted: their lookups queue and fire the
        // pool's worker after the batch window instead of hitting the cache
        val misses = listOf("4", "3").map { id -> async { pool.getStatus(id) } }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(status(4), status(3)), misses.map { it.await() })
        assertEquals(listOf(setOf("3", "4")), lookup.calls)
    }

    @Test
    fun `a 429 waits for the reset window then retries the same page`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { index ->
            if (index == 0) MastoApiException(429, "limited", "2026-10-15T00:00:10Z") else null
        }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(
            accounts, pool, account, size = 1,
            rateLimitBackoff = RateLimitBackoff(clock = testClock()),
        )

        // the retry asks for the identical page and fires at the reset
        // (t+10s) plus the default 800ms margin
        assertEquals(
            listOf(
                GetStatusesCall("42", 1, null, true, 0),
                GetStatusesCall("42", 1, null, true, 10_800),
            ),
            accounts.calls,
        )
        assertEquals(listOf("40"), result.map { it.id })
    }

    @Test
    fun `a 429 without a usable reset header waits the fallback delay`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { index ->
            if (index == 0) MastoApiException(429, "limited", null) else null
        }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(
            accounts, pool, account, size = 1,
            rateLimitBackoff = RateLimitBackoff(fallbackDelay = 2.seconds, clock = testClock()),
        )

        // no header to wait for, so the fixed fallback applies
        assertEquals(
            listOf(
                GetStatusesCall("42", 1, null, true, 0),
                GetStatusesCall("42", 1, null, true, 2_000),
            ),
            accounts.calls,
        )
        assertEquals(listOf("40"), result.map { it.id })
    }

    @Test
    fun `429 retries are not bounded by the transient retry budget`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        // twelve rate limits exceed the transient budget of 10
        // retries, yet the scan keeps waiting: the instance paces us,
        // it has not failed us
        accounts.failure = { if (it < 12) MastoApiException(429, "limited", null) else null }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(
            accounts, pool, account, size = 1,
            rateLimitBackoff = RateLimitBackoff(fallbackDelay = 2.seconds, clock = testClock()),
        )

        assertEquals(13, accounts.calls.size)
        assertEquals((0..12).map { it * 2_000L }, accounts.calls.map { it.at })
        assertEquals(listOf("40"), result.map { it.id })
    }

    @Test
    fun `a 5xx page is retried after a delay and then succeeds`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { if (it == 0) MastoApiException(502, "bad gateway") else null }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(accounts, pool, account, size = 1)

        // one transient retry, spaced by the fixed 10s retry delay
        assertEquals(
            listOf(
                GetStatusesCall("42", 1, null, true, 0),
                GetStatusesCall("42", 1, null, true, 10_000),
            ),
            accounts.calls,
        )
        assertEquals(listOf("40"), result.map { it.id })
    }

    @Test
    fun `a transport error is retried and then succeeds`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { if (it == 0) IOException("connection reset") else null }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val result = performInitialScan(accounts, pool, account, size = 1)

        assertEquals(
            listOf(
                GetStatusesCall("42", 1, null, true, 0),
                GetStatusesCall("42", 1, null, true, 10_000),
            ),
            accounts.calls,
        )
        assertEquals(listOf("40"), result.map { it.id })
    }

    @Test
    fun `persistent 5xx pages fail the scan after bounded retries`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { MastoApiException(503, "unavailable") }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(accounts, pool, account, size = 1)
        }

        // the last error propagates after one attempt plus 10 retries,
        // 10s apart — failing the boot beats stalling it on an
        // instance that stays broken
        assertEquals(503, e.httpCode)
        assertEquals((0..10).map { it * 10_000L }, accounts.calls.map { it.at })
    }

    @Test
    fun `a custom transient retry budget and delay are honored`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { MastoApiException(503, "unavailable") }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(
                accounts, pool, account, size = 1,
                transientMaxRetry = 2, transientRetryDelay = 1.seconds,
            )
        }

        // one attempt plus the custom 2-retry budget, spaced by the
        // custom delay instead of the built-in 10s
        assertEquals(503, e.httpCode)
        assertEquals((0..2).map { it * 1_000L }, accounts.calls.map { it.at })
    }

    @Test
    fun `a zero transient retry budget fails the scan on the first transient error`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        // a blip the built-in budget would ride out
        accounts.failure = { if (it == 0) MastoApiException(502, "bad gateway") else null }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(
                accounts, pool, account, size = 1,
                transientMaxRetry = 0, transientRetryDelay = 1.seconds,
            )
        }

        assertEquals(502, e.httpCode)
        assertEquals(1, accounts.calls.size)
    }

    @Test
    fun `a negative transient retry budget fails fast before any fetch`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<IllegalArgumentException> {
            performInitialScan(accounts, pool, account, size = 1, transientMaxRetry = -1)
        }

        assertTrue(e.message!!.contains("transientMaxRetry"))
        assertEquals(0, accounts.calls.size)
    }

    @Test
    fun `a zero transient retry delay fails fast before any fetch`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<IllegalArgumentException> {
            performInitialScan(accounts, pool, account, size = 1, transientRetryDelay = Duration.ZERO)
        }

        assertTrue(e.message!!.contains("transientRetryDelay"))
        assertEquals(0, accounts.calls.size)
    }

    @Test
    fun `a non-transient api error fails fast without burning retries`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { MastoApiException(401, "The access token is invalid") }
        val pool = poolFor(FakeStatusesClient(emptyMap()))

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(accounts, pool, account, size = 1)
        }

        // a rejected token would fail every retry the same way, so the
        // first failure propagates immediately
        assertEquals(401, e.httpCode)
        assertEquals(1, accounts.calls.size)
    }
}
