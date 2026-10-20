package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.config.requireValidRetryDelay
import info.skyblond.fediverse.toot.trans.config.requireValidTransientMaxRetry
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Perform one bounded newest-window scan: fetch the latest [size] toots
 * of [account] (newest first, page by page via [limit]) and return them
 * in old-to-new order.
 *
 * The application uses this at boot, and the scan loop repeats it while
 * no rolling cursor exists yet (nothing was ever dropped, see
 * `PendingQueue.dropHeadAndRecordCursor`); everything newer than a
 * cursor is the incremental scan's job ([performIncrementalScan]).
 *
 * Each returned entry carries the instant its page fetch was issued
 * ([FetchedStatus.fetchedAt]), which the cooldown check uses; no status
 * is seeded into the pool here — that is `PendingQueue.feed`'s job, so
 * there is a single place that feeds the pool.
 *
 * Page fetches survive transient failure, retrying the identical
 * page: a 429 waits out the instance's reset via [rateLimitBackoff]
 * (see [RateLimitBackoff] for the wait computation) like the pool's
 * worker does; 5xx responses and transport errors retry
 * [transientMaxRetry] times, spaced [transientRetryDelay] apart,
 * before the last error propagates. Other API errors (e.g. a rejected
 * token) propagate immediately — retrying them could only turn a
 * config bug into an endless retry loop.
 *
 * [size] must be non-negative; 0 skips the scan entirely. [limit] must
 * be positive. The scan also stops early when the account holds fewer
 * toots than [size]. The returned order is `editedAt ?: createdAt`
 * ascending, so a toot edited after newer ones were posted counts as
 * current as of its edit.
 *
 * [transientMaxRetry] must be non-negative, where 0 disables transient
 * retries; [transientRetryDelay] must be finite and at least 1ms.
 * */
suspend fun performInitialScan(
    accountsClient: AccountsClient,
    account: CredentialAccount,
    size: Int,
    limit: Int = AccountsClient.GET_STATUSES_LIMIT,
    rateLimitBackoff: RateLimitBackoff = RateLimitBackoff(),
    transientMaxRetry: Int = DEFAULT_TRANSIENT_MAX_RETRY,
    transientRetryDelay: Duration = DEFAULT_TRANSIENT_RETRY_DELAY,
): List<FetchedStatus> {
    require(size >= 0) { "size must be non-negative, got $size" }
    require(limit > 0) { "limit must be positive, got $limit" }
    requireValidTransientMaxRetry("transientMaxRetry", transientMaxRetry)
    requireValidRetryDelay("transientRetryDelay", transientRetryDelay)
    if (size == 0) return emptyList()

    logger.info { "Performing newest-window scan for account ${account.username}: size=$size, pageLimit=$limit" }

    var fetchedCount = 0
    var lastId: String? = null
    val result = ArrayList<FetchedStatus>(size)
    while (fetchedCount < size) {
        val roundLimit = minOf(size - fetchedCount, limit)

        val statuses = fetchPageWithRetry(
            accountsClient, account, roundLimit, maxId = lastId, minId = null,
            rateLimitBackoff, transientMaxRetry, transientRetryDelay,
        )

        if (statuses.isEmpty()) break
        result.addAll(statuses)
        fetchedCount += statuses.size
        lastId = statuses.last().status.id

        logger.info { "Fetched ${statuses.size} statuses, total $fetchedCount, goal $size" }

        // a short page means the account ran out of toots
        if (statuses.size < roundLimit) break
    }

    logger.info { "Finished newest-window scan, fetched $fetchedCount statuses" }

    // the endpoint lists newest first, and edits must count as the toot
    // becoming current again (see the KDoc); stable sort keeps ties in
    // fetch order
    return result.sortedBy { it.status.editedAt ?: it.status.createdAt }
}

/**
 * Perform one incremental scan: fetch every toot newer than [cursor]
 * (page by page via [limit]) and return them in old-to-new order. The
 * cursor is the rolling `PendingQueue.cursor` of the window; resuming
 * right after it keeps already processed toots out of the scan.
 *
 * Paging uses `min_id`, each page resuming right above the previous
 * page's newest id, so a catch-up wider than one page cannot skip the
 * middle — unlike `since_id`, which always answers with the newest
 * page. The endpoint lists newest first (the same assumption the
 * bounded scan relies on), so a page's first entry carries its
 * greatest id. The scan stops at a short or empty page: the account
 * has no more toots above the cursor.
 *
 * The result deliberately includes entries already queued: feeding
 * them again replaces the queued copy with this fresher observation
 * (see `PendingQueue.feed`), which is also the only way a cooling or
 * otherwise blocked entry becomes eligible again, because the cooldown
 * reads the observation's fetch time.
 *
 * Failure handling is shared with [performInitialScan]; its KDoc
 * documents the 429/transient policy.
 *
 * [cursor] must not be blank; [limit] must be positive;
 * [transientMaxRetry] and [transientRetryDelay] must satisfy the same
 * contracts as in [performInitialScan].
 * */
suspend fun performIncrementalScan(
    accountsClient: AccountsClient,
    account: CredentialAccount,
    cursor: String,
    limit: Int = AccountsClient.GET_STATUSES_LIMIT,
    rateLimitBackoff: RateLimitBackoff = RateLimitBackoff(),
    transientMaxRetry: Int = DEFAULT_TRANSIENT_MAX_RETRY,
    transientRetryDelay: Duration = DEFAULT_TRANSIENT_RETRY_DELAY,
): List<FetchedStatus> {
    require(cursor.isNotBlank()) { "cursor must not be blank" }
    require(limit > 0) { "limit must be positive, got $limit" }
    requireValidTransientMaxRetry("transientMaxRetry", transientMaxRetry)
    requireValidRetryDelay("transientRetryDelay", transientRetryDelay)

    logger.info {
        "Performing incremental scan for account ${account.username}: cursor=$cursor, pageLimit=$limit"
    }

    var fetchedCount = 0
    var minId = cursor
    val result = ArrayList<FetchedStatus>()
    while (true) {
        val statuses = fetchPageWithRetry(
            accountsClient, account, limit, maxId = null, minId = minId,
            rateLimitBackoff, transientMaxRetry, transientRetryDelay,
        )

        if (statuses.isEmpty()) break
        result.addAll(statuses)
        fetchedCount += statuses.size
        // newest first: the first entry has the greatest id, and the
        // next page resumes right above it
        minId = statuses.first().status.id

        logger.info { "Fetched ${statuses.size} statuses, total $fetchedCount" }

        // a short page means the account ran out of toots above the cursor
        if (statuses.size < limit) break
    }

    logger.info { "Finished incremental scan, fetched $fetchedCount statuses" }

    // same order contract as the bounded scan
    return result.sortedBy { it.status.editedAt ?: it.status.createdAt }
}

/**
 * Fetch one scan page, retrying the identical request on transient
 * failure, see [performInitialScan]'s KDoc for the policy.
 *
 * Exactly one of [maxId] and [minId] is set by the callers: [maxId]
 * pages downward through the newest window, [minId] pages upward from
 * the rolling cursor.
 *
 * 429s retry without bound, mirroring the pool's worker: the instance
 * says when we may proceed, and giving up would fail the scan for no
 * gain. Only [MastoApiException] and [IOException] are caught, so
 * cancellation (including during a backoff [delay]) propagates.
 * */
private suspend fun fetchPageWithRetry(
    accountsClient: AccountsClient,
    account: CredentialAccount,
    limit: Int,
    maxId: String?,
    minId: String?,
    rateLimitBackoff: RateLimitBackoff,
    transientMaxRetry: Int,
    transientRetryDelay: Duration,
): List<FetchedStatus> {
    var transientRetries = 0
    while (true) {
        try {
            // a boost embeds someone else's toot and carries no text of the
            // owner's own, so there is nothing to translate; filter them
            // out server-side instead of skipping the wrappers downstream
            return accountsClient.getStatuses(
                account.id, limit = limit, maxId = maxId, minId = minId, excludeReblogs = true
            )
        } catch (e: MastoApiException) {
            if (e.httpCode == 429) {
                val wait = rateLimitBackoff.waitDuration(e)
                logger.warn {
                    "Rate limited scanning ${account.username}'s statuses, backing off $wait " +
                            "(reset: ${e.rateLimitReset ?: "no usable header"})"
                }
                delay(wait)
            } else if (e.httpCode >= 500 && transientRetries < transientMaxRetry) {
                transientRetries++
                logger.warn(e) {
                    "Server error scanning ${account.username}'s statuses, " +
                            "retry $transientRetries/$transientMaxRetry in $transientRetryDelay"
                }
                delay(transientRetryDelay)
            } else {
                throw e
            }
        } catch (e: IOException) {
            if (transientRetries >= transientMaxRetry) throw e
            transientRetries++
            logger.warn(e) {
                "Transport error scanning ${account.username}'s statuses, " +
                        "retry $transientRetries/$transientMaxRetry in $transientRetryDelay"
            }
            delay(transientRetryDelay)
        }
    }
}

/**
 * Retry budget per page for transient failures (5xx, transport): one
 * initial attempt plus this many, then the last error propagates —
 * fail fast instead of stalling a scan on a broken instance. 429s do
 * not count: the instance is pacing us, not failing us.
 *
 * Built-in default for [performInitialScan]'s and
 * [performIncrementalScan]'s [transientMaxRetry]; the config package's
 * default provider reuses it so the deploy default cannot drift apart
 * from the programmatic/test default, same pattern as
 * [StatusesCachePool]'s companion object.
 * */
const val DEFAULT_TRANSIENT_MAX_RETRY = 10

/**
 * Spacing between transient retries: long enough for a proxy blip or
 * an overloaded instance to recover, short enough not to stall a scan.
 *
 * Built-in default for [performInitialScan]'s and
 * [performIncrementalScan]'s [transientRetryDelay], reused by the
 * config package's default provider; see
 * [DEFAULT_TRANSIENT_MAX_RETRY]'s KDoc for the single-sourcing.
 * */
val DEFAULT_TRANSIENT_RETRY_DELAY: Duration = 10.seconds
