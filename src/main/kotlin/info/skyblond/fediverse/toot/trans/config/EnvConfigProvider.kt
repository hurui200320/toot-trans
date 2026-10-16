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

    override val poolCacheTtl: Duration?
        get() = duration("POOL_CACHE_TTL")

    override val poolMaxCacheEntries: Int?
        get() = int("POOL_MAX_CACHE_ENTRIES")

    override val backoffResetMargin: Duration?
        get() = duration("BACKOFF_RESET_MARGIN")

    override val backoffFallbackDelay: Duration?
        get() = duration("BACKOFF_FALLBACK_DELAY")

    override val backoffMaxWaitDuration: Duration?
        get() = duration("BACKOFF_MAX_WAIT_DURATION")

    override val initialScanSize: Int?
        get() = int("INITIAL_SCAN_SIZE")

    override val scanTransientMaxRetry: Int?
        get() = int("SCAN_TRANSIENT_MAX_RETRY")

    override val scanTransientRetryDelay: Duration?
        get() = duration("SCAN_TRANSIENT_RETRY_DELAY")

    override val translationCooldown: Duration?
        get() = duration("TRANSLATION_COOLDOWN")

    override val targetLanguage: String?
        get() = string("TARGET_LANGUAGE")

    override val targetLangCode: String?
        get() = string("TARGET_LANG_CODE")

    private fun duration(name: String): Duration? =
        parseDurationConfig(name, env(name))

    private fun int(name: String): Int? =
        parseIntConfig(name, env(name))

    private fun string(name: String): String? =
        parseStringConfig(env(name))
}
