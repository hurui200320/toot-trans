package info.skyblond.fediverse.toot.trans.config

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
     * Extra wait after a 429 reset before the [StatusesPool] resumes, see its KDoc.
     * */
    val poolResetMargin: Duration?

    /**
     * [StatusesPool] backoff when a 429 reset header is unusable, see its KDoc.
     * */
    val poolFallbackDelay: Duration?

    /**
     * Sanity bound on [StatusesPool] reset-based backoff, see its KDoc.
     * */
    val poolMaxWaitDuration: Duration?
}
