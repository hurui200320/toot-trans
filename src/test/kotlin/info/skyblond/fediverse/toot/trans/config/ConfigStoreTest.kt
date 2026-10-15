package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_MAX_RETRY
import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_RETRY_DELAY
import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
        scanTransientMaxRetry: Int? = null,
        scanTransientRetryDelay: Duration? = null,
        targetLanguage: String? = null,
        targetLangCode: String? = null,
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
        override val scanTransientMaxRetry: Int? = scanTransientMaxRetry
        override val scanTransientRetryDelay: Duration? = scanTransientRetryDelay
        override val targetLanguage: String? = targetLanguage
        override val targetLangCode: String? = targetLangCode
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
    fun `scan transient configs follow the first non-null provider`() {
        val store = ConfigStore(
            listOf(
                provider(scanTransientMaxRetry = 5, scanTransientRetryDelay = 30.seconds),
                provider(scanTransientMaxRetry = 9, scanTransientRetryDelay = 40.seconds),
            )
        )

        assertEquals(5, store.scanTransientMaxRetry)
        assertEquals(30.seconds, store.scanTransientRetryDelay)
    }

    @Test
    fun `scan transient configs skip nulls and fall back to later providers`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                provider(scanTransientRetryDelay = 30.seconds),
                provider(scanTransientRetryDelay = 40.seconds),
            )
        )

        assertEquals(30.seconds, store.scanTransientRetryDelay)
    }

    @Test
    fun `malformed scan transient config in an earlier provider fails fast instead of falling through`() {
        val store = ConfigStore(
            listOf(
                // simulates a malformed env var, e.g. SCAN_TRANSIENT_RETRY_DELAY=soon
                EnvConfigProvider(mapOf("SCAN_TRANSIENT_RETRY_DELAY" to "soon")::get),
                provider(scanTransientRetryDelay = 30.seconds),
            )
        )

        val exception = assertFailsWith<IllegalArgumentException> { store.scanTransientRetryDelay }
        assertTrue(exception.message!!.contains("SCAN_TRANSIENT_RETRY_DELAY"))
    }

    @Test
    fun `throws when no provider supplies a scan transient config`() {
        val store = ConfigStore(listOf(provider()))

        val exception = assertFailsWith<MissingConfigException> { store.scanTransientMaxRetry }
        assertTrue(exception.message!!.contains("scan transient max retry"))
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

        assertEquals(StatusesPool.DEFAULT_BATCH_WINDOW, store.poolBatchWindow)
        assertEquals(StatusesPool.DEFAULT_CACHE_TTL, store.poolCacheTtl)
        assertEquals(StatusesPool.DEFAULT_MAX_CACHE_ENTRIES, store.poolMaxCacheEntries)
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
    fun `built-in defaults fill unconfigured scan transient configs`() {
        val store = ConfigStore(
            listOf(
                provider(),
                DefaultConfigProvider()
            )
        )

        assertEquals(DEFAULT_TRANSIENT_MAX_RETRY, store.scanTransientMaxRetry)
        assertEquals(DEFAULT_TRANSIENT_RETRY_DELAY, store.scanTransientRetryDelay)
    }
}
