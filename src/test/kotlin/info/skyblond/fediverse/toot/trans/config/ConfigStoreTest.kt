package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
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
        poolRequestInterval: Duration? = null,
        poolCacheTtl: Duration? = null,
        poolMaxCacheEntries: Int? = null,
        poolResetMargin: Duration? = null,
        poolFallbackDelay: Duration? = null,
        poolMaxRetry: Duration? = null,
    ): ConfigProvider = object : ConfigProvider {
        override val mastoHost: String? = mastoHost
        override val accessToken: String? = accessToken
        override val poolBatchWindow: Duration? = poolBatchWindow
        override val poolRequestInterval: Duration? = poolRequestInterval
        override val poolCacheTtl: Duration? = poolCacheTtl
        override val poolMaxCacheEntries: Int? = poolMaxCacheEntries
        override val poolResetMargin: Duration? = poolResetMargin
        override val poolFallbackDelay: Duration? = poolFallbackDelay
        override val poolMaxRetry: Duration? = poolMaxRetry
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
                provider(poolRequestInterval = 2.seconds),
                provider(poolRequestInterval = 3.seconds),
            )
        )

        assertEquals(2.seconds, store.poolRequestInterval)
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

        val exception = assertFailsWith<MissingConfigException> { store.poolFallbackDelay }
        assertTrue(exception.message!!.contains("pool fallback delay"))
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
        assertEquals(StatusesPool.DEFAULT_REQUEST_INTERVAL, store.poolRequestInterval)
        assertEquals(StatusesPool.DEFAULT_CACHE_TTL, store.poolCacheTtl)
        assertEquals(StatusesPool.DEFAULT_MAX_CACHE_ENTRIES, store.poolMaxCacheEntries)
        assertEquals(StatusesPool.DEFAULT_RESET_MARGIN, store.poolResetMargin)
        assertEquals(StatusesPool.DEFAULT_FALLBACK_DELAY, store.poolFallbackDelay)
        assertEquals(StatusesPool.DEFAULT_MAX_RETRY, store.poolMaxRetry)
    }
}
