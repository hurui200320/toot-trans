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

    override val backoffResetMargin: Duration
        get() = getOrThrow("backoff reset margin") { it.backoffResetMargin }

    override val backoffFallbackDelay: Duration
        get() = getOrThrow("backoff fallback delay") { it.backoffFallbackDelay }

    override val backoffMaxWaitDuration: Duration
        get() = getOrThrow("backoff max wait duration") { it.backoffMaxWaitDuration }

    override val initialScanSize: Int
        get() = getOrThrow("initial scan size") { it.initialScanSize }

    override val scanTransientMaxRetry: Int
        get() = getOrThrow("scan transient max retry") { it.scanTransientMaxRetry }

    override val scanTransientRetryDelay: Duration
        get() = getOrThrow("scan transient retry delay") { it.scanTransientRetryDelay }

    override val translationCooldown: Duration
        get() = getOrThrow("translation cooldown") { it.translationCooldown }

    override val targetLanguage: String
        get() = getOrThrow("target language") { it.targetLanguage }

    override val targetLangCode: String
        get() = getOrThrow("target lang code") { it.targetLangCode }

    private fun <T> getOrThrow(
        name: String,
        mapper: (ConfigProvider) -> T?
    ): T = configProviders.firstNotNullOfOrNull(mapper)
        ?: throw MissingConfigException(name)
}
