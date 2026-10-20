package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
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
        val minId: String? = null,
    )

    /**
     * Serves [statuses] (stored newest first, like the endpoint lists
     * them) with `max_id` and `min_id` pagination: a `max_id` request
     * returns the newest page below `maxId`, a `min_id` request returns
     * the oldest page above `minId`, both up to `limit` and both newest
     * first, mirroring the real endpoint's reversed upward page. Ids are
     * numeric strings, compared as numbers. Each returned page is
     * stamped with the virtual fetch time, like the real transport does.
     * [failure] scripts an exception by call index (0-based, failed
     * attempts included); null lets the call succeed.
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
        ): List<FetchedStatus> {
            calls += GetStatusesCall(
                accountId, limit, maxId, excludeReblogs, scope.testScheduler.currentTime, minId
            )
            failure(calls.size - 1)?.let { throw it }
            val fetchedAt = epoch.plusMillis(scope.testScheduler.currentTime)
            val page = if (minId != null) {
                // the oldest `limit` toots above minId, newest first
                statuses.filter { it.id.toInt() > minId.toInt() }.takeLast(limit)
            } else {
                statuses
                    .filter { maxId == null || it.id.toInt() < maxId.toInt() }
                    .take(limit)
            }
            return page.map { FetchedStatus(it, fetchedAt) }
        }
    }

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
    fun `paginates with maxId and returns old to new`() = runTest {
        // 50 toots, id 50 the newest
        val statuses = (50 downTo 1).map { status(it) }
        val accounts = FakeAccountsClient(statuses, this)

        val result = performInitialScan(accounts, account, size = 50)

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
        assertEquals((1..50).map { it.toString() }, result.map { it.status.id })
    }

    @Test
    fun `a size below the page limit fetches a single clamped call`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)

        val result = performInitialScan(accounts, account, size = 5)

        // the request asks for only what is still needed
        assertEquals(listOf(GetStatusesCall("42", 5, null, true, 0)), accounts.calls)
        assertEquals((46..50).map { it.toString() }, result.map { it.status.id })
    }

    @Test
    fun `size zero skips the scan entirely`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)

        val result = performInitialScan(accounts, account, size = 0)

        assertEquals(0, result.size)
        assertTrue(accounts.calls.isEmpty())
    }

    @Test
    fun `negative size fails fast before any call`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)

        val e = assertFailsWith<IllegalArgumentException> {
            performInitialScan(accounts, account, size = -1)
        }

        assertTrue(e.message!!.contains("size"))
        assertTrue(accounts.calls.isEmpty())
    }

    @Test
    fun `a non-positive page limit fails fast before any call`() = runTest {
        val accounts = FakeAccountsClient((50 downTo 1).map { status(it) }, this)

        for (limit in listOf(0, -1)) {
            val e = assertFailsWith<IllegalArgumentException> {
                performInitialScan(accounts, account, size = 1, limit = limit)
            }

            assertTrue(e.message!!.contains("limit"))
        }
        assertTrue(accounts.calls.isEmpty())
    }

    @Test
    fun `stops early when the account has fewer toots than requested`() = runTest {
        // only 5 toots exist, a full page is requested
        val accounts = FakeAccountsClient((5 downTo 1).map { status(it) }, this)

        val result = performInitialScan(accounts, account, size = 40)

        // the short page means no second request
        assertEquals(listOf(GetStatusesCall("42", 40, null, true, 0)), accounts.calls)
        assertEquals((1..5).map { it.toString() }, result.map { it.status.id })
    }

    @Test
    fun `an account without toots yields an empty scan`() = runTest {
        val accounts = FakeAccountsClient(emptyList(), this)

        val result = performInitialScan(accounts, account, size = 40)

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

        val result = performInitialScan(accounts, account, size = 3)

        // by editedAt ?: createdAt: 2 (t+2), 3 (t+3), 1 (edited t+10)
        assertEquals(listOf("2", "3", "1"), result.map { it.status.id })
    }

    @Test
    fun `a 429 waits for the reset window then retries the same page`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { index ->
            if (index == 0) MastoApiException(429, "limited", "2026-10-15T00:00:10Z") else null
        }

        val result = performInitialScan(
            accounts, account, size = 1,
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
        assertEquals(listOf("40"), result.map { it.status.id })
    }

    @Test
    fun `a 429 without a usable reset header waits the fallback delay`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { index ->
            if (index == 0) MastoApiException(429, "limited", null) else null
        }

        val result = performInitialScan(
            accounts, account, size = 1,
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
        assertEquals(listOf("40"), result.map { it.status.id })
    }

    @Test
    fun `429 retries are not bounded by the transient retry budget`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        // twelve rate limits exceed the transient budget of 10
        // retries, yet the scan keeps waiting: the instance paces us,
        // it has not failed us
        accounts.failure = { if (it < 12) MastoApiException(429, "limited", null) else null }

        val result = performInitialScan(
            accounts, account, size = 1,
            rateLimitBackoff = RateLimitBackoff(fallbackDelay = 2.seconds, clock = testClock()),
        )

        assertEquals(13, accounts.calls.size)
        assertEquals((0..12).map { it * 2_000L }, accounts.calls.map { it.at })
        assertEquals(listOf("40"), result.map { it.status.id })
    }

    @Test
    fun `a 5xx page is retried after a delay and then succeeds`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { if (it == 0) MastoApiException(502, "bad gateway") else null }

        val result = performInitialScan(accounts, account, size = 1)

        // one transient retry, spaced by the fixed 10s retry delay
        assertEquals(
            listOf(
                GetStatusesCall("42", 1, null, true, 0),
                GetStatusesCall("42", 1, null, true, 10_000),
            ),
            accounts.calls,
        )
        assertEquals(listOf("40"), result.map { it.status.id })
    }

    @Test
    fun `a transport error is retried and then succeeds`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { if (it == 0) IOException("connection reset") else null }

        val result = performInitialScan(accounts, account, size = 1)

        assertEquals(
            listOf(
                GetStatusesCall("42", 1, null, true, 0),
                GetStatusesCall("42", 1, null, true, 10_000),
            ),
            accounts.calls,
        )
        assertEquals(listOf("40"), result.map { it.status.id })
    }

    @Test
    fun `persistent 5xx pages fail the scan after bounded retries`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { MastoApiException(503, "unavailable") }

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(accounts, account, size = 1)
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

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(
                accounts, account, size = 1,
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

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(
                accounts, account, size = 1,
                transientMaxRetry = 0, transientRetryDelay = 1.seconds,
            )
        }

        assertEquals(502, e.httpCode)
        assertEquals(1, accounts.calls.size)
    }

    @Test
    fun `a negative transient retry budget fails fast before any fetch`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)

        val e = assertFailsWith<IllegalArgumentException> {
            performInitialScan(accounts, account, size = 1, transientMaxRetry = -1)
        }

        assertTrue(e.message!!.contains("transientMaxRetry"))
        assertEquals(0, accounts.calls.size)
    }

    @Test
    fun `a zero transient retry delay fails fast before any fetch`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)

        val e = assertFailsWith<IllegalArgumentException> {
            performInitialScan(accounts, account, size = 1, transientRetryDelay = Duration.ZERO)
        }

        assertTrue(e.message!!.contains("transientRetryDelay"))
        assertEquals(0, accounts.calls.size)
    }

    @Test
    fun `a non-transient api error fails fast without burning retries`() = runTest {
        val accounts = FakeAccountsClient((40 downTo 1).map { status(it) }, this)
        accounts.failure = { MastoApiException(401, "The access token is invalid") }

        val e = assertFailsWith<MastoApiException> {
            performInitialScan(accounts, account, size = 1)
        }

        // a rejected token would fail every retry the same way, so the
        // first failure propagates immediately
        assertEquals(401, e.httpCode)
        assertEquals(1, accounts.calls.size)
    }

    @Test
    fun `incremental scan pages upward from the cursor and returns old to new`() = runTest {
        // 100 toots, id 100 the newest; everything above id 10 is new
        val accounts = FakeAccountsClient((100 downTo 1).map { status(it) }, this)

        val result = performIncrementalScan(accounts, account, cursor = "10")

        // each page resumes right above the previous page's newest entry
        // (50, then 90); the last page is short (91..100), proving the
        // account is caught up
        assertEquals(
            listOf(
                GetStatusesCall("42", 40, null, true, 0, "10"),
                GetStatusesCall("42", 40, null, true, 0, "50"),
                GetStatusesCall("42", 40, null, true, 0, "90"),
            ),
            accounts.calls,
        )
        // old to new, which equals id order here
        assertEquals((11..100).map { it.toString() }, result.map { it.status.id })
    }

    @Test
    fun `incremental scan stops at a short page above the cursor`() = runTest {
        val accounts = FakeAccountsClient((100 downTo 1).map { status(it) }, this)

        val result = performIncrementalScan(accounts, account, cursor = "95")

        // five newer toots: a single short page, no empty follow-up
        assertEquals(listOf(GetStatusesCall("42", 40, null, true, 0, "95")), accounts.calls)
        assertEquals((96..100).map { it.toString() }, result.map { it.status.id })
    }

    @Test
    fun `incremental scan with nothing newer stops after one empty page`() = runTest {
        val accounts = FakeAccountsClient((100 downTo 1).map { status(it) }, this)

        val result = performIncrementalScan(accounts, account, cursor = "100")

        assertEquals(0, result.size)
        assertEquals(listOf(GetStatusesCall("42", 40, null, true, 0, "100")), accounts.calls)
    }

    @Test
    fun `incremental scan caps the page at the configured limit`() = runTest {
        val accounts = FakeAccountsClient((10 downTo 1).map { status(it) }, this)

        val result = performIncrementalScan(accounts, account, cursor = "3", limit = 5)

        // 7 newer toots, so a full 5-toot page then a short one
        assertEquals(
            listOf(
                GetStatusesCall("42", 5, null, true, 0, "3"),
                GetStatusesCall("42", 5, null, true, 0, "8"),
            ),
            accounts.calls,
        )
        assertEquals((4..10).map { it.toString() }, result.map { it.status.id })
    }

    @Test
    fun `incremental scan waits out a 429 and retries the same page`() = runTest {
        val accounts = FakeAccountsClient((100 downTo 1).map { status(it) }, this)
        accounts.failure = { index ->
            if (index == 0) MastoApiException(429, "limited", "2026-10-15T00:00:10Z") else null
        }

        val result = performIncrementalScan(
            accounts, account, cursor = "99",
            rateLimitBackoff = RateLimitBackoff(clock = testClock()),
        )

        // the retry asks for the identical page at the reset (t+10s)
        // plus the default 800ms margin
        assertEquals(
            listOf(
                GetStatusesCall("42", 40, null, true, 0, "99"),
                GetStatusesCall("42", 40, null, true, 10_800, "99"),
            ),
            accounts.calls,
        )
        assertEquals(listOf("100"), result.map { it.status.id })
    }

    @Test
    fun `incremental scan propagates a non-transient error immediately`() = runTest {
        val accounts = FakeAccountsClient((100 downTo 1).map { status(it) }, this)
        accounts.failure = { MastoApiException(401, "The access token is invalid") }

        val e = assertFailsWith<MastoApiException> {
            performIncrementalScan(accounts, account, cursor = "10")
        }

        assertEquals(401, e.httpCode)
        assertEquals(1, accounts.calls.size)
    }

    @Test
    fun `a blank incremental cursor fails fast before any call`() = runTest {
        val accounts = FakeAccountsClient((10 downTo 1).map { status(it) }, this)

        val e = assertFailsWith<IllegalArgumentException> {
            performIncrementalScan(accounts, account, cursor = " ")
        }

        assertTrue(e.message!!.contains("cursor"))
        assertEquals(0, accounts.calls.size)
    }

    @Test
    fun `a non-positive incremental page limit fails fast before any call`() = runTest {
        val accounts = FakeAccountsClient((10 downTo 1).map { status(it) }, this)

        for (limit in listOf(0, -1)) {
            val e = assertFailsWith<IllegalArgumentException> {
                performIncrementalScan(accounts, account, cursor = "1", limit = limit)
            }

            assertTrue(e.message!!.contains("limit"))
        }
        assertEquals(0, accounts.calls.size)
    }
}
