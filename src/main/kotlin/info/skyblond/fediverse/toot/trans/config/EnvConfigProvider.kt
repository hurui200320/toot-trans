package info.skyblond.fediverse.toot.trans.config

import kotlin.time.Duration

/**
 * Read config from the environment variables.
 *
 * The [env] lookup is injectable so tests can supply a fake environment;
 * it defaults to the real process environment.
 * */
class EnvConfigProvider(
    private val env: (String) -> String? = System::getenv,
) : ConfigProvider {
    override val mastoHost: String?
        get() = env("MASTO_HOST")

    override val accessToken: String?
        get() = env("ACCESS_TOKEN")

    override val poolBatchWindow: Duration?
        get() = duration("POOL_BATCH_WINDOW")

    override val poolRequestInterval: Duration?
        get() = duration("POOL_REQUEST_INTERVAL")

    override val poolCacheTtl: Duration?
        get() = duration("POOL_CACHE_TTL")

    override val poolMaxCacheEntries: Int?
        get() = int("POOL_MAX_CACHE_ENTRIES")

    override val poolResetMargin: Duration?
        get() = duration("POOL_RESET_MARGIN")

    override val poolFallbackDelay: Duration?
        get() = duration("POOL_FALLBACK_DELAY")

    override val poolMaxRetry: Duration?
        get() = duration("POOL_MAX_RETRY")

    private fun duration(name: String): Duration? =
        parseDurationConfig(name, env(name))

    private fun int(name: String): Int? =
        parseIntConfig(name, env(name))
}
