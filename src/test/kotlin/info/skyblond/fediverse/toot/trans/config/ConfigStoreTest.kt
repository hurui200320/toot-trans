package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_MAX_RETRY
import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_RETRY_DELAY
import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import info.skyblond.fediverse.toot.trans.llm.client.OkHttpChatClient
import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.ReplyChainContext
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

class ConfigStoreTest {
    private fun provider(
        mastoHost: String? = null,
        accessToken: String? = null,
        poolBatchWindow: Duration? = null,
        poolCacheTtl: Duration? = null,
        poolMaxCacheEntries: Int? = null,
        backoffResetMargin: Duration? = null,
        backoffFallbackDelay: Duration? = null,
        backoffMaxWaitDuration: Duration? = null,
        initialScanSize: Int? = null,
        transientMaxRetry: Int? = null,
        transientRetryDelay: Duration? = null,
        targetLanguage: String? = null,
        targetLangCode: String? = null,
        translationCooldown: Duration? = null,
        replyChainDepth: Int? = null,
        llmBaseUrl: String? = null,
        llmApiKey: String? = null,
        llmConnectTimeout: Duration? = null,
        llmReadTimeout: Duration? = null,
        llmWriteTimeout: Duration? = null,
        llmModel: String? = null,
        llmMaxCompletionTokens: Int? = null,
        llmReasoningEffort: ReasoningEffort? = null,
        llmReqExtra: JsonObject? = null,
    ): ConfigProvider = object : ConfigProvider {
        override val mastoHost: String? = mastoHost
        override val accessToken: String? = accessToken
        override val poolBatchWindow: Duration? = poolBatchWindow
        override val poolCacheTtl: Duration? = poolCacheTtl
        override val poolMaxCacheEntries: Int? = poolMaxCacheEntries
        override val backoffResetMargin: Duration? = backoffResetMargin
        override val backoffFallbackDelay: Duration? = backoffFallbackDelay
        override val backoffMaxWaitDuration: Duration? = backoffMaxWaitDuration
        override val initialScanSize: Int? = initialScanSize
        override val transientMaxRetry: Int? = transientMaxRetry
        override val transientRetryDelay: Duration? = transientRetryDelay
        override val targetLanguage: String? = targetLanguage
        override val targetLangCode: String? = targetLangCode
        override val translationCooldown: Duration? = translationCooldown
        override val replyChainDepth: Int? = replyChainDepth
        override val llmBaseUrl: String? = llmBaseUrl
        override val llmApiKey: String? = llmApiKey
        override val llmConnectTimeout: Duration? = llmConnectTimeout
        override val llmReadTimeout: Duration? = llmReadTimeout
        override val llmWriteTimeout: Duration? = llmWriteTimeout
        override val llmModel: String? = llmModel
        override val llmMaxCompletionTokens: Int? = llmMaxCompletionTokens
        override val llmReasoningEffort: ReasoningEffort? = llmReasoningEffort
        override val llmReqExtra: JsonObject? = llmReqExtra
    }

    @Test
    fun `first provider wins`() {
        val store = ConfigStore(
            listOf(
                provider(mastoHost = "https://first.example/", accessToken = "token1"),
                provider(mastoHost = "https://second.example/", accessToken = "token2"),
            )
        )

        assertEquals("https://first.example/", store.mastoHost)
        assertEquals("token1", store.accessToken)
    }

    @Test
    fun `null values are skipped and later providers are used`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                // only host, no token
                provider(mastoHost = "https://second.example/"),
                // both configured, should only be used for the token
                provider(mastoHost = "https://third.example/", accessToken = "token"),
            )
        )

        assertEquals("https://second.example/", store.mastoHost)
        assertEquals("token", store.accessToken)
    }

    @Test
    fun `throws when no provider supplies the config`() {
        val store = ConfigStore(
            listOf(
                provider(),
                provider(mastoHost = "https://partial.example/"),
            )
        )

        // host is available, token is not
        assertEquals("https://partial.example/", store.mastoHost)
        val exception = assertFailsWith<MissingConfigException> { store.accessToken }
        assertTrue(exception.message!!.contains("access token"))
    }

    @Test
    fun `throws on empty provider list`() {
        val store = ConfigStore(emptyList())

        val exception = assertFailsWith<MissingConfigException> { store.mastoHost }
        assertTrue(exception.message!!.contains("masto host"))
    }

    @Test
    fun `pool configs follow the first non-null provider`() {
        val store = ConfigStore(
            listOf(
                provider(poolBatchWindow = 2.seconds, poolMaxCacheEntries = 100),
                provider(poolBatchWindow = 3.seconds, poolMaxCacheEntries = 200),
            )
        )

        assertEquals(2.seconds, store.poolBatchWindow)
        assertEquals(100, store.poolMaxCacheEntries)
    }

    @Test
    fun `pool configs skip nulls and fall back to later providers`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                provider(poolCacheTtl = 2.seconds),
                provider(poolCacheTtl = 3.seconds),
            )
        )

        assertEquals(2.seconds, store.poolCacheTtl)
    }

    @Test
    fun `malformed value in an earlier provider fails fast instead of falling through`() {
        val store = ConfigStore(
            listOf(
                // simulates a malformed env var, e.g. POOL_BATCH_WINDOW=fast
                EnvConfigProvider(mapOf("POOL_BATCH_WINDOW" to "fast")::get),
                provider(poolBatchWindow = 2.seconds),
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { store.poolBatchWindow }
        assertTrue(exception.message!!.contains("POOL_BATCH_WINDOW"))
    }

    @Test
    fun `throws when no provider supplies a pool config`() {
        val store = ConfigStore(listOf(provider()))

        val exception = assertFailsWith<MissingConfigException> { store.poolBatchWindow }
        assertTrue(exception.message!!.contains("pool batch window"))
    }

    @Test
    fun `throws when no provider supplies a backoff config`() {
        val store = ConfigStore(listOf(provider()))

        val exception = assertFailsWith<MissingConfigException> { store.backoffFallbackDelay }
        assertTrue(exception.message!!.contains("backoff fallback delay"))
    }

    @Test
    fun `initial scan size follows the first non-null provider`() {
        val store = ConfigStore(
            listOf(
                provider(initialScanSize = 40),
                provider(initialScanSize = 80),
            )
        )

        assertEquals(40, store.initialScanSize)
    }

    @Test
    fun `initial scan size skips nulls and falls back to later providers`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                provider(initialScanSize = 80),
            )
        )

        assertEquals(80, store.initialScanSize)
    }

    @Test
    fun `malformed initial scan size in an earlier provider fails fast instead of falling through`() {
        val store = ConfigStore(
            listOf(
                // simulates a malformed env var, e.g. INITIAL_SCAN_SIZE=many
                EnvConfigProvider(mapOf("INITIAL_SCAN_SIZE" to "many")::get),
                provider(initialScanSize = 40),
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { store.initialScanSize }
        assertTrue(exception.message!!.contains("INITIAL_SCAN_SIZE"))
    }

    @Test
    fun `throws when no provider supplies the initial scan size`() {
        val store = ConfigStore(listOf(provider()))

        val exception = assertFailsWith<MissingConfigException> { store.initialScanSize }
        assertTrue(exception.message!!.contains("initial scan size"))
    }

    @Test
    fun `transient configs follow the first non-null provider`() {
        val store = ConfigStore(
            listOf(
                provider(transientMaxRetry = 5, transientRetryDelay = 30.seconds),
                provider(transientMaxRetry = 9, transientRetryDelay = 40.seconds),
            )
        )

        assertEquals(5, store.transientMaxRetry)
        assertEquals(30.seconds, store.transientRetryDelay)
    }

    @Test
    fun `transient configs skip nulls and fall back to later providers`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                provider(transientRetryDelay = 30.seconds),
                provider(transientRetryDelay = 40.seconds),
            )
        )

        assertEquals(30.seconds, store.transientRetryDelay)
    }

    @Test
    fun `malformed transient config in an earlier provider fails fast instead of falling through`() {
        val store = ConfigStore(
            listOf(
                // simulates a malformed env var, e.g. TRANSIENT_RETRY_DELAY=soon
                EnvConfigProvider(mapOf("TRANSIENT_RETRY_DELAY" to "soon")::get),
                provider(transientRetryDelay = 30.seconds),
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { store.transientRetryDelay }
        assertTrue(exception.message!!.contains("TRANSIENT_RETRY_DELAY"))
    }

    @Test
    fun `throws when no provider supplies a transient config`() {
        val store = ConfigStore(listOf(provider()))

        val exception = assertFailsWith<MissingConfigException> { store.transientMaxRetry }
        assertTrue(exception.message!!.contains("transient max retry"))
    }

    @Test
    fun `target lang configs follow the first non-null provider`() {
        val store = ConfigStore(
            listOf(
                provider(targetLanguage = "English", targetLangCode = "en"),
                provider(targetLanguage = "Japanese", targetLangCode = "ja"),
            )
        )

        assertEquals("English", store.targetLanguage)
        assertEquals("en", store.targetLangCode)
    }

    @Test
    fun `target lang configs skip nulls and fall back to later providers`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                provider(targetLanguage = "Japanese", targetLangCode = "ja"),
                provider(targetLanguage = "German", targetLangCode = "de"),
            )
        )

        assertEquals("Japanese", store.targetLanguage)
        assertEquals("ja", store.targetLangCode)
    }

    @Test
    fun `throws when no provider supplies the target lang configs`() {
        val store = ConfigStore(listOf(provider()))

        val missingLanguage = assertFailsWith<MissingConfigException> { store.targetLanguage }
        assertTrue(missingLanguage.message!!.contains("target language"))
        val missingCode = assertFailsWith<MissingConfigException> { store.targetLangCode }
        assertTrue(missingCode.message!!.contains("target lang code"))
    }

    @Test
    fun `target lang configs have no built-in default`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        // like the host and token, these are required: the built-in
        // default provider must not silently invent a target language
        val missingLanguage = assertFailsWith<MissingConfigException> { store.targetLanguage }
        assertTrue(missingLanguage.message!!.contains("target language"))
        val missingCode = assertFailsWith<MissingConfigException> { store.targetLangCode }
        assertTrue(missingCode.message!!.contains("target lang code"))
    }

    @Test
    fun `built-in defaults fill unconfigured pool configs`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        assertEquals(StatusesCachePool.DEFAULT_BATCH_WINDOW, store.poolBatchWindow)
        assertEquals(StatusesCachePool.DEFAULT_CACHE_TTL, store.poolCacheTtl)
        assertEquals(StatusesCachePool.DEFAULT_MAX_CACHE_ENTRIES, store.poolMaxCacheEntries)
    }

    @Test
    fun `built-in defaults fill unconfigured backoff configs`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        assertEquals(RateLimitBackoff.DEFAULT_RESET_MARGIN, store.backoffResetMargin)
        assertEquals(RateLimitBackoff.DEFAULT_FALLBACK_DELAY, store.backoffFallbackDelay)
        assertEquals(RateLimitBackoff.DEFAULT_MAX_WAIT_DURATION, store.backoffMaxWaitDuration)
    }

    @Test
    fun `built-in default for the initial scan size is one default page`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        assertEquals(AccountsClient.GET_STATUSES_LIMIT, store.initialScanSize)
    }

    @Test
    fun `built-in defaults fill unconfigured transient configs`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        assertEquals(DEFAULT_TRANSIENT_MAX_RETRY, store.transientMaxRetry)
        assertEquals(DEFAULT_TRANSIENT_RETRY_DELAY, store.transientRetryDelay)
    }

    @Test
    fun `LLM configs follow the first non-null provider`() {
        val store = ConfigStore(
            listOf(
                provider(
                    llmBaseUrl = "https://first.example/v1",
                    llmApiKey = "first-key",
                    llmConnectTimeout = 5.seconds,
                    llmModel = "first-model",
                    llmMaxCompletionTokens = 1000,
                    llmReasoningEffort = ReasoningEffort.LOW,
                    llmReqExtra = JsonObject(mapOf("temperature" to JsonPrimitive(0.2))),
                ),
                provider(
                    llmBaseUrl = "https://second.example/v1",
                    llmApiKey = "second-key",
                    llmConnectTimeout = 6.seconds,
                    llmModel = "second-model",
                    llmMaxCompletionTokens = 2000,
                    llmReasoningEffort = ReasoningEffort.HIGH,
                    llmReqExtra = JsonObject(emptyMap()),
                ),
            )
        )

        assertEquals("https://first.example/v1", store.llmBaseUrl)
        assertEquals("first-key", store.llmApiKey)
        assertEquals(5.seconds, store.llmConnectTimeout)
        assertEquals("first-model", store.llmModel)
        assertEquals(1000, store.llmMaxCompletionTokens)
        assertEquals(ReasoningEffort.LOW, store.llmReasoningEffort)
        assertEquals("0.2", store.llmReqExtra["temperature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `throws when no provider supplies a required LLM config`() {
        val store = ConfigStore(listOf(provider()))

        val missingBaseUrl = assertFailsWith<MissingConfigException> { store.llmBaseUrl }
        assertTrue(missingBaseUrl.message!!.contains("llm base url"))
        val missingApiKey = assertFailsWith<MissingConfigException> { store.llmApiKey }
        assertTrue(missingApiKey.message!!.contains("llm api key"))
        val missingModel = assertFailsWith<MissingConfigException> { store.llmModel }
        assertTrue(missingModel.message!!.contains("llm model"))
        val missingMaxTokens = assertFailsWith<MissingConfigException> { store.llmMaxCompletionTokens }
        assertTrue(missingMaxTokens.message!!.contains("llm max completion tokens"))
    }

    @Test
    fun `llm reasoning effort is optional and resolves to null when unset`() {
        // nobody configured it: null is the resolved value, the request
        // will omit the field, not a MissingConfigException
        assertNull(ConfigStore(listOf(provider())).llmReasoningEffort)

        // still first-non-null like every other setting: a later provider
        // can supply the value when earlier ones leave it unset
        val configured = ConfigStore(listOf(provider(), provider(llmReasoningEffort = ReasoningEffort.LOW)))
        assertEquals(ReasoningEffort.LOW, configured.llmReasoningEffort)
    }

    @Test
    fun `built-in defaults fill unconfigured LLM timeouts and request extra`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        assertEquals(OkHttpChatClient.RECOMMENDED_CONNECT_TIMEOUT.toKotlinDuration(), store.llmConnectTimeout)
        assertEquals(OkHttpChatClient.RECOMMENDED_READ_TIMEOUT.toKotlinDuration(), store.llmReadTimeout)
        assertEquals(OkHttpChatClient.RECOMMENDED_WRITE_TIMEOUT.toKotlinDuration(), store.llmWriteTimeout)
        assertEquals(JsonObject(emptyMap()), store.llmReqExtra)
    }

    @Test
    fun `malformed LLM config in an earlier provider fails fast instead of falling through`() {
        val store = ConfigStore(
            listOf(
                // simulates a malformed env var, e.g. LLM_REASONING_EFFORT=whenever
                EnvConfigProvider(mapOf("LLM_REASONING_EFFORT" to "whenever")::get),
                provider(llmReasoningEffort = ReasoningEffort.LOW),
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { store.llmReasoningEffort }
        assertTrue(exception.message!!.contains("LLM_REASONING_EFFORT"))
    }

    @Test
    fun `cooldown respects precedence null fallback and built-in default`() {
        val store = ConfigStore(listOf(
            provider(), provider(translationCooldown = 0.seconds), provider(translationCooldown = 10.seconds),
        ))
        assertEquals(0.seconds, store.translationCooldown)
        assertEquals(Cooldown.DEFAULT_DURATION, ConfigStore(listOf(provider(), DefaultConfigProvider())).translationCooldown)
    }

    @Test
    fun `missing and malformed cooldown fail rather than falling through`() {
        val missing = assertFailsWith<MissingConfigException> { ConfigStore(listOf(provider())).translationCooldown }
        assertTrue(missing.message!!.contains("translation cooldown"))
        val store = ConfigStore(listOf(
            EnvConfigProvider(mapOf("TRANSLATION_COOLDOWN" to "soon")::get), DefaultConfigProvider(),
        ))
        val malformed = assertFailsWith<IllegalArgumentException> { store.translationCooldown }
        assertTrue(malformed.message!!.contains("TRANSLATION_COOLDOWN"))
    }

    @Test
    fun `reply chain depth respects precedence null fallback and built-in default`() {
        // first non-null wins, so the zero of the middle provider is used
        val store = ConfigStore(listOf(
            provider(), provider(replyChainDepth = 0), provider(replyChainDepth = 5),
        ))
        assertEquals(0, store.replyChainDepth)
        val defaults = ConfigStore(listOf(provider(), DefaultConfigProvider()))
        assertEquals(ReplyChainContext.DEFAULT_MAX_DEPTH, defaults.replyChainDepth)
    }

    @Test
    fun `missing and malformed reply chain depth fail rather than falling through`() {
        val missing = assertFailsWith<MissingConfigException> { ConfigStore(listOf(provider())).replyChainDepth }
        assertTrue(missing.message!!.contains("reply chain depth"))
        val store = ConfigStore(listOf(
            EnvConfigProvider(mapOf("REPLY_CHAIN_DEPTH" to "deep")::get), DefaultConfigProvider(),
        ))
        val malformed = assertFailsWith<IllegalArgumentException> { store.replyChainDepth }
        assertTrue(malformed.message!!.contains("REPLY_CHAIN_DEPTH"))
    }
}
