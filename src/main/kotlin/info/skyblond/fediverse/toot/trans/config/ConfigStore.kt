package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import kotlin.time.Duration


/**
 * Aggregate different [ConfigProvider]s.
 *
 * For each config, providers are asked in the given order and
 * the first non-null value wins.
 *
 * This is a terminal provider: unlike normal providers, it never returns null
 * as a fallback signal. If no provider can provide the config, it throws
 * [MissingConfigException]. Hence it MUST NOT be nested in another
 * [ConfigStore]'s provider list, otherwise the fallback of the outer store
 * is broken.
 * */
class ConfigStore(
    private val configProviders: List<ConfigProvider>,
) : ConfigProvider {
    override val mastoHost: String
        get() = getOrThrow("masto host") { it.mastoHost }

    override val accessToken: String
        get() = getOrThrow("access token") { it.accessToken }

    override val poolBatchWindow: Duration
        get() = getOrThrow("pool batch window") { it.poolBatchWindow }

    override val poolCacheTtl: Duration
        get() = getOrThrow("pool cache TTL") { it.poolCacheTtl }

    override val poolMaxCacheEntries: Int
        get() = getOrThrow("pool max cache entries") { it.poolMaxCacheEntries }

    override val poolResetMargin: Duration
        get() = getOrThrow("pool reset margin") { it.poolResetMargin }

    override val poolFallbackDelay: Duration
        get() = getOrThrow("pool fallback delay") { it.poolFallbackDelay }

    override val poolMaxWaitDuration: Duration
        get() = getOrThrow("pool max wait duration") { it.poolMaxWaitDuration }

    private fun <T> getOrThrow(
        name: String,
        mapper: (ConfigProvider) -> T?
    ): T = configProviders.firstNotNullOfOrNull(mapper)
        ?: throw MissingConfigException(name)
}
