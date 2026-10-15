package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlin.time.Duration

class DefaultConfigProvider : ConfigProvider {
    // required config, no default
    override val mastoHost: String? = null

    // required config, no default
    override val accessToken: String? = null

    // optional pool configs, defaults are single-sourced in
    // [StatusesPool]'s companion object so they cannot drift apart
    override val poolBatchWindow: Duration = StatusesPool.DEFAULT_BATCH_WINDOW
    override val poolRequestInterval: Duration = StatusesPool.DEFAULT_REQUEST_INTERVAL
    override val poolCacheTtl: Duration = StatusesPool.DEFAULT_CACHE_TTL
    override val poolMaxCacheEntries: Int = StatusesPool.DEFAULT_MAX_CACHE_ENTRIES
    override val poolResetMargin: Duration = StatusesPool.DEFAULT_RESET_MARGIN
    override val poolFallbackDelay: Duration = StatusesPool.DEFAULT_FALLBACK_DELAY
    override val poolMaxRetry: Duration = StatusesPool.DEFAULT_MAX_RETRY
}
