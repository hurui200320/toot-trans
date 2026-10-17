package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionRequest
import info.skyblond.fediverse.toot.trans.llm.client.OkHttpChatClient
import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import info.skyblond.fediverse.toot.trans.llm.client.RetryingChatClient
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import kotlinx.serialization.json.JsonObject
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
     * Miss collection window of the [StatusesCachePool], see its KDoc.
     * */
    val poolBatchWindow: Duration?

    /**
     * Insertion-based expiry of the [StatusesCachePool] cache, see its KDoc.
     * */
    val poolCacheTtl: Duration?

    /**
     * Entry limit of the [StatusesCachePool] LRU cache, see its KDoc.
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
     * Retry budget for transient failures (5xx, transport), shared
     * globally: the boot-up scan spends it per page and the LLM chat
     * transport spends it as its attempt budget; 0 disables such
     * retries. Each consumer's retry policy lives with the consumer:
     * see `performInitialScan` in `Scan.kt` and [RetryingChatClient].
     * */
    val transientMaxRetry: Int?

    /**
     * Spacing between the transient retries, see [transientMaxRetry]
     * for the consumers and their policies.
     * */
    val transientRetryDelay: Duration?

    /**
     * Delay after creation/edit before a toot is eligible; zero disables it.
     * The cooldown timing contract lives on `Cooldown` in
     * `masto/statuses/Cooldown.kt`; eligibility beyond it lives on
     * `PendingQueue.feed` in `masto/statuses/PendingQueue.kt`.
     */
    val translationCooldown: Duration?

    /**
     * Target language for translation in plain text.
     * For example, "English" or "Simplified Chinese".
     * */
    val targetLanguage: String?

    /**
     * Target language code for translation, sent to the instance as-is
     * when posting the translated replies. The instance validates the
     * tag case-sensitively against its supported locale list, so what
     * it stores may differ from what was posted: tags on the list
     * round-trip unchanged (`en`, `zh-CN`, `zh-TW`); anything else
     * whose base tag is on the list is reduced to that base (`en-US`
     * and even `en-GB` become `en`, `zh-cn` becomes `zh`); anything
     * else makes the instance silently fall back to the account's
     * posting language — the exact rules live server-side, in
     * Mastodon's `PostStatusService` and `valid_locale_or_nil` from
     * `languages_helper.rb`. Fetched statuses are matched against this
     * config exactly (see `PendingQueue.feed`), so the value must be a tag the
     * instance stores as-is.
     * For example, "en", "zh", "zh-CN".
     * */
    val targetLangCode: String?

    /**
     * API root of the OpenAI-compatible chat completions provider,
     * for example `https://api.openai.com/v1`; the transport appends
     * `/chat/completions`, see [OkHttpChatClient].
     * */
    val llmBaseUrl: String?

    /**
     * Bearer key for the chat completions provider. Secret: must
     * never be logged.
     * */
    val llmApiKey: String?

    /**
     * Connect timeout of the LLM chat transport, applied to a client
     * derived from the shared OkHttp client; 0 disables the timeout
     * (OkHttp semantics). Defaults to
     * [OkHttpChatClient.RECOMMENDED_CONNECT_TIMEOUT], whose KDoc
     * explains the value.
     * */
    val llmConnectTimeout: Duration?

    /**
     * Read timeout of the LLM chat transport, same wiring as
     * [llmConnectTimeout]. Defaults to
     * [OkHttpChatClient.RECOMMENDED_READ_TIMEOUT], whose KDoc
     * explains the value.
     * */
    val llmReadTimeout: Duration?

    /**
     * Write timeout of the LLM chat transport, same wiring as
     * [llmConnectTimeout]. Defaults to
     * [OkHttpChatClient.RECOMMENDED_WRITE_TIMEOUT], whose KDoc
     * explains the value.
     * */
    val llmWriteTimeout: Duration?

    /**
     * Model ID sent with every chat completion, see
     * [ChatCompletionRequest.model].
     * */
    val llmModel: String?

    /**
     * Upper bound on tokens generated per completion, including
     * thinking tokens, see [ChatCompletionRequest.maxCompletionTokens].
     * */
    val llmMaxCompletionTokens: Int?

    /**
     * Reasoning depth sent with every chat completion, see
     * [ReasoningEffort]; the value is one of the enum's names
     * (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`),
     * matched case-insensitively.
     *
     * Optional: null when no source configures it, and the field is
     * then omitted from every request — the way to target providers
     * that reject `reasoning_effort`, see
     * [ChatCompletionRequest.reasoningEffort].
     * */
    val llmReasoningEffort: ReasoningEffort?

    /**
     * Provider-specific top-level body fields merged into every chat
     * completion request, given as a JSON object (e.g.
     * `{"temperature": 0.2}`); defaults to `{}` (no extra fields) when
     * no source configures it. Keys owned by the client are rejected,
     * see [ChatCompletionRequest.extra].
     * */
    val llmReqExtra: JsonObject?
}
