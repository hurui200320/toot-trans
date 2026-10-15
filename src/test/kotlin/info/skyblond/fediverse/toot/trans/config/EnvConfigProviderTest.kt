package info.skyblond.fediverse.toot.trans.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class EnvConfigProviderTest {
    @Test
    fun `reads values from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "MASTO_HOST" to "https://m.skyblond.info/",
                "ACCESS_TOKEN" to "some-token",
            )::get
        )

        assertEquals("https://m.skyblond.info/", provider.mastoHost)
        assertEquals("some-token", provider.accessToken)
    }

    @Test
    fun `reads pool configs from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "POOL_BATCH_WINDOW" to "500ms",
                "POOL_REQUEST_INTERVAL" to "2s",
                "POOL_CACHE_TTL" to "5m",
                "POOL_MAX_CACHE_ENTRIES" to "200",
                "POOL_RESET_MARGIN" to "1s",
                "POOL_FALLBACK_DELAY" to "PT1M30S",
                "POOL_MAX_RETRY" to "2h",
            )::get
        )

        assertEquals(500.milliseconds, provider.poolBatchWindow)
        assertEquals(2.seconds, provider.poolRequestInterval)
        assertEquals(5.minutes, provider.poolCacheTtl)
        assertEquals(200, provider.poolMaxCacheEntries)
        assertEquals(1.seconds, provider.poolResetMargin)
        assertEquals(90.seconds, provider.poolFallbackDelay)
        assertEquals(2.hours, provider.poolMaxRetry)
    }

    @Test
    fun `returns null for missing env vars`() {
        val provider = EnvConfigProvider(emptyMap<String, String>()::get)

        assertNull(provider.mastoHost)
        assertNull(provider.accessToken)
        assertNull(provider.poolBatchWindow)
        assertNull(provider.poolMaxCacheEntries)
    }

    @Test
    fun `blank env values are treated as not configured`() {
        val provider = EnvConfigProvider(mapOf("POOL_BATCH_WINDOW" to "   ")::get)

        assertNull(provider.poolBatchWindow)
    }

    @Test
    fun `invalid env duration fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("POOL_BATCH_WINDOW" to "fast")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.poolBatchWindow }

        assertTrue(e.message!!.contains("POOL_BATCH_WINDOW"))
    }

    @Test
    fun `invalid env integer fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("POOL_MAX_CACHE_ENTRIES" to "lots")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.poolMaxCacheEntries }

        assertTrue(e.message!!.contains("POOL_MAX_CACHE_ENTRIES"))
    }
}
