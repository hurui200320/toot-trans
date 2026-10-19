package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_MAX_RETRY
import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_RETRY_DELAY
import info.skyblond.fediverse.toot.trans.llm.client.OkHttpChatClient
import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import info.skyblond.fediverse.toot.trans.masto.statuses.ReplyChainContext
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

class DefaultConfigProvider : ConfigProvider {
    // required config, no default
    override val mastoHost: String? = null

    // required config, no default
    override val accessToken: String? = null

    // optional pool and backoff configs, defaults are single-sourced in
    // [StatusesCachePool]'s and [RateLimitBackoff]'s companion objects so
    // they cannot drift apart
    override val poolBatchWindow: Duration = StatusesCachePool.DEFAULT_BATCH_WINDOW
    override val poolCacheTtl: Duration = StatusesCachePool.DEFAULT_CACHE_TTL
    override val poolMaxCacheEntries: Int = StatusesCachePool.DEFAULT_MAX_CACHE_ENTRIES
    override val backoffResetMargin: Duration = RateLimitBackoff.DEFAULT_RESET_MARGIN
    override val backoffFallbackDelay: Duration = RateLimitBackoff.DEFAULT_FALLBACK_DELAY
    override val backoffMaxWaitDuration: Duration = RateLimitBackoff.DEFAULT_MAX_WAIT_DURATION

    // optional, defaults to one default page of the account statuses
    // endpoint, single-sourced from [AccountsClient.GET_STATUSES_LIMIT]
    // so it cannot drift apart either
    override val initialScanSize: Int = AccountsClient.GET_STATUSES_LIMIT

    // optional, defaults for the global transient-failure retry policy,
    // single-sourced in `Scan.kt`'s constants so they cannot drift
    // apart either
    override val transientMaxRetry: Int = DEFAULT_TRANSIENT_MAX_RETRY
    override val transientRetryDelay: Duration = DEFAULT_TRANSIENT_RETRY_DELAY

    // optional, default single-sourced from [Cooldown]'s companion object
    // so the deploy default cannot drift apart from the programmatic/test
    // default
    override val translationCooldown: Duration = Cooldown.DEFAULT_DURATION

    // optional, default single-sourced from [PendingQueue]'s companion
    // object like the cooldown above
    override val translationMaxAge: Duration = PendingQueue.DEFAULT_MAX_AGE

    // optional, default single-sourced from [ReplyChainContext]'s companion
    // object like the other component defaults
    override val replyChainDepth: Int = ReplyChainContext.DEFAULT_MAX_DEPTH

    // required config, no default
    override val targetLanguage: String? = null

    // required config, no default
    override val targetLangCode: String? = null

    // required LLM config, no default
    override val llmBaseUrl: String? = null
    override val llmApiKey: String? = null
    override val llmModel: String? = null
    override val llmMaxCompletionTokens: Int? = null

    // optional: the empty object means no provider-specific request
    // fields, see [ConfigProvider.llmReqExtra]
    override val llmReqExtra: JsonObject = JsonObject(emptyMap())

    // optional: null means the request omits `reasoning_effort` entirely,
    // see [ConfigProvider.llmReasoningEffort]
    override val llmReasoningEffort: ReasoningEffort? = null

    // optional, defaults are the transport's recommended LLM timeouts,
    // single-sourced from [OkHttpChatClient]'s companion so they cannot
    // drift apart either
    override val llmConnectTimeout: Duration = OkHttpChatClient.RECOMMENDED_CONNECT_TIMEOUT.toKotlinDuration()
    override val llmReadTimeout: Duration = OkHttpChatClient.RECOMMENDED_READ_TIMEOUT.toKotlinDuration()
    override val llmWriteTimeout: Duration = OkHttpChatClient.RECOMMENDED_WRITE_TIMEOUT.toKotlinDuration()
}
