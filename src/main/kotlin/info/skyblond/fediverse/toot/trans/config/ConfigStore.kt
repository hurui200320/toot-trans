package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration


/**
 * Aggregate different [ConfigProvider]s.
 *
 * For each config, providers are asked in the given order and
 * the first non-null value wins.
 *
 * This is a terminal provider: unlike normal providers, it never returns null
 * as a fallback signal. If no provider can provide the config, it throws
 * [MissingConfigException] — except for the optional settings whose override
 * declares null a legal resolved value (see [llmReasoningEffort], resolved
 * through [getOrNull]). Hence it MUST NOT be nested in another
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

    override val transientMaxRetry: Int
        get() = getOrThrow("transient max retry") { it.transientMaxRetry }

    override val transientRetryDelay: Duration
        get() = getOrThrow("transient retry delay") { it.transientRetryDelay }

    override val translationCooldown: Duration
        get() = getOrThrow("translation cooldown") { it.translationCooldown }

    override val targetLanguage: String
        get() = getOrThrow("target language") { it.targetLanguage }

    override val targetLangCode: String
        get() = getOrThrow("target lang code") { it.targetLangCode }

    override val llmBaseUrl: String
        get() = getOrThrow("llm base url") { it.llmBaseUrl }

    override val llmApiKey: String
        get() = getOrThrow("llm api key") { it.llmApiKey }

    override val llmConnectTimeout: Duration
        get() = getOrThrow("llm connect timeout") { it.llmConnectTimeout }

    override val llmReadTimeout: Duration
        get() = getOrThrow("llm read timeout") { it.llmReadTimeout }

    override val llmWriteTimeout: Duration
        get() = getOrThrow("llm write timeout") { it.llmWriteTimeout }

    override val llmModel: String
        get() = getOrThrow("llm model") { it.llmModel }

    override val llmMaxCompletionTokens: Int
        get() = getOrThrow("llm max completion tokens") { it.llmMaxCompletionTokens }

    // optional: null means nobody configured it, which omits the field
    // from every request, see [ConfigProvider.llmReasoningEffort]
    override val llmReasoningEffort: ReasoningEffort?
        get() = getOrNull { it.llmReasoningEffort }

    override val llmReqExtra: JsonObject
        get() = getOrThrow("llm request extra") { it.llmReqExtra }

    private fun <T> getOrThrow(
        name: String,
        mapper: (ConfigProvider) -> T?
    ): T = configProviders.firstNotNullOfOrNull(mapper)
        ?: throw MissingConfigException(name)

    /**
     * [getOrThrow] for the optional settings: null when no provider
     * supplies a value, because unset is a legal resolved value there
     * (see the override's KDoc). A malformed value still fails fast
     * inside the provider that parses it, so a typo never silently
     * resolves to unset.
     * */
    private fun <T> getOrNull(
        mapper: (ConfigProvider) -> T?
    ): T? = configProviders.firstNotNullOfOrNull(mapper)
}
