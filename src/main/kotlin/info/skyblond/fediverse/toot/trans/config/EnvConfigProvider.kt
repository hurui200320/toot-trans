package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import kotlinx.serialization.json.JsonObject
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

    override val transientMaxRetry: Int?
        get() = int("TRANSIENT_MAX_RETRY")

    override val transientRetryDelay: Duration?
        get() = duration("TRANSIENT_RETRY_DELAY")

    override val translationCooldown: Duration?
        get() = duration("TRANSLATION_COOLDOWN")

    override val targetLanguage: String?
        get() = string("TARGET_LANGUAGE")

    override val targetLangCode: String?
        get() = string("TARGET_LANG_CODE")

    override val llmBaseUrl: String?
        get() = string("LLM_BASE_URL")

    override val llmApiKey: String?
        get() = string("LLM_API_KEY")

    override val llmConnectTimeout: Duration?
        get() = duration("LLM_CONNECT_TIMEOUT")

    override val llmReadTimeout: Duration?
        get() = duration("LLM_READ_TIMEOUT")

    override val llmWriteTimeout: Duration?
        get() = duration("LLM_WRITE_TIMEOUT")

    override val llmModel: String?
        get() = string("LLM_MODEL")

    override val llmMaxCompletionTokens: Int?
        get() = int("LLM_MAX_COMPLETION_TOKENS")

    override val llmReasoningEffort: ReasoningEffort?
        get() = enumValue("LLM_REASONING_EFFORT")

    override val llmReqExtra: JsonObject?
        get() = jsonObject("LLM_REQ_EXTRA")

    private fun duration(name: String): Duration? =
        parseDurationConfig(name, env(name))

    private fun int(name: String): Int? =
        parseIntConfig(name, env(name))

    private fun string(name: String): String? =
        parseStringConfig(env(name))

    private inline fun <reified T : Enum<T>> enumValue(name: String): T? =
        parseEnumConfig(name, env(name))

    private fun jsonObject(name: String): JsonObject? =
        parseJsonObjectConfig(name, env(name))
}
