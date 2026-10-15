package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_MAX_RETRY
import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_RETRY_DELAY
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlin.time.Duration

class DefaultConfigProvider : ConfigProvider {
    // required config, no default
    override val mastoHost: String? = null

    // required config, no default
    override val accessToken: String? = null

    // optional pool and backoff configs, defaults are single-sourced in
    // [StatusesPool]'s and [RateLimitBackoff]'s companion objects so
    // they cannot drift apart
    override val poolBatchWindow: Duration = StatusesPool.DEFAULT_BATCH_WINDOW
    override val poolCacheTtl: Duration = StatusesPool.DEFAULT_CACHE_TTL
    override val poolMaxCacheEntries: Int = StatusesPool.DEFAULT_MAX_CACHE_ENTRIES
    override val backoffResetMargin: Duration = RateLimitBackoff.DEFAULT_RESET_MARGIN
    override val backoffFallbackDelay: Duration = RateLimitBackoff.DEFAULT_FALLBACK_DELAY
    override val backoffMaxWaitDuration: Duration = RateLimitBackoff.DEFAULT_MAX_WAIT_DURATION

    // optional, defaults to one default page of the account statuses
    // endpoint, single-sourced from [AccountsClient.GET_STATUSES_LIMIT]
    // so it cannot drift apart either
    override val initialScanSize: Int = AccountsClient.GET_STATUSES_LIMIT

    // optional, defaults for the scan's transient-failure retry policy,
    // single-sourced in `Scan.kt`'s constants so they cannot drift
    // apart either
    override val scanTransientMaxRetry: Int = DEFAULT_TRANSIENT_MAX_RETRY
    override val scanTransientRetryDelay: Duration = DEFAULT_TRANSIENT_RETRY_DELAY
}
