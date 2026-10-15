package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlin.time.Duration

/**
 * Provide configuration.
 *
 * Each provider should only report configs from its own source.
 * When not configured, return null so the [ConfigStore] can fallback.
 * */
interface ConfigProvider {
    /**
     * Host of the instance, for example: `https://m.skyblond.info/`
     * */
    val mastoHost: String?

    /**
     * Your access token.
     * */
    val accessToken: String?

    /**
     * Miss collection window of the [StatusesPool], see its KDoc.
     * */
    val poolBatchWindow: Duration?

    /**
     * Insertion-based expiry of the [StatusesPool] cache, see its KDoc.
     * */
    val poolCacheTtl: Duration?

    /**
     * Entry limit of the [StatusesPool] LRU cache, see its KDoc.
     * */
    val poolMaxCacheEntries: Int?

    /**
     * Extra wait after a 429 reset, see [RateLimitBackoff].
     * */
    val backoffResetMargin: Duration?

    /**
     * Fixed backoff when a 429 reset header is unusable, see [RateLimitBackoff].
     * */
    val backoffFallbackDelay: Duration?

    /**
     * Sanity bound on reset-based backoff, see [RateLimitBackoff].
     * */
    val backoffMaxWaitDuration: Duration?

    /**
     * Amount of the owner's latest toots the boot-up scan fetches;
     * 0 disables the scan. The scan semantics live with the scan
     * itself: see `performInitialScan` in `Scan.kt`.
     * */
    val initialScanSize: Int?

    /**
     * Retry budget per page for transient failures (5xx, transport)
     * during the boot-up scan; 0 disables such retries. The retry
     * policy lives with the scan itself: see `performInitialScan`
     * in `Scan.kt`.
     * */
    val scanTransientMaxRetry: Int?

    /**
     * Spacing between the boot-up scan's transient retries, see
     * `performInitialScan` in `Scan.kt`.
     * */
    val scanTransientRetryDelay: Duration?
}
