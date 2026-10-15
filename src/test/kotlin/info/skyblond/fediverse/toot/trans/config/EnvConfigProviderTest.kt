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
                "POOL_CACHE_TTL" to "5m",
                "POOL_MAX_CACHE_ENTRIES" to "200",
            )::get
        )

        assertEquals(500.milliseconds, provider.poolBatchWindow)
        assertEquals(5.minutes, provider.poolCacheTtl)
        assertEquals(200, provider.poolMaxCacheEntries)
    }

    @Test
    fun `reads backoff configs from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "BACKOFF_RESET_MARGIN" to "1s",
                "BACKOFF_FALLBACK_DELAY" to "PT1M30S",
                "BACKOFF_MAX_WAIT_DURATION" to "2h",
            )::get
        )

        assertEquals(1.seconds, provider.backoffResetMargin)
        assertEquals(90.seconds, provider.backoffFallbackDelay)
        assertEquals(2.hours, provider.backoffMaxWaitDuration)
    }

    @Test
    fun `returns null for missing env vars`() {
        val provider = EnvConfigProvider(emptyMap<String, String>()::get)

        assertNull(provider.mastoHost)
        assertNull(provider.accessToken)
        assertNull(provider.poolBatchWindow)
        assertNull(provider.poolMaxCacheEntries)
        assertNull(provider.backoffFallbackDelay)
        assertNull(provider.initialScanSize)
        assertNull(provider.scanTransientMaxRetry)
        assertNull(provider.scanTransientRetryDelay)
        assertNull(provider.targetLanguage)
        assertNull(provider.targetLangCode)
    }

    @Test
    fun `blank env values are treated as not configured`() {
        val provider = EnvConfigProvider(mapOf("POOL_BATCH_WINDOW" to "   ")::get)

        assertNull(provider.poolBatchWindow)
    }

    @Test
    fun `reads the initial scan size from the environment`() {
        val provider = EnvConfigProvider(mapOf("INITIAL_SCAN_SIZE" to "20")::get)

        assertEquals(20, provider.initialScanSize)
    }

    @Test
    fun `reads scan transient configs from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "SCAN_TRANSIENT_MAX_RETRY" to "5",
                "SCAN_TRANSIENT_RETRY_DELAY" to "30s",
            )::get
        )

        assertEquals(5, provider.scanTransientMaxRetry)
        assertEquals(30.seconds, provider.scanTransientRetryDelay)
    }

    @Test
    fun `reads target lang configs from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "TARGET_LANGUAGE" to "Simplified Chinese",
                // a regional tag, taken as-is like any other value
                "TARGET_LANG_CODE" to "zh-CN",
            )::get
        )

        assertEquals("Simplified Chinese", provider.targetLanguage)
        assertEquals("zh-CN", provider.targetLangCode)
    }

    @Test
    fun `blank target lang env values are treated as not configured`() {
        val provider = EnvConfigProvider(
            mapOf(
                "TARGET_LANGUAGE" to "   ",
                "TARGET_LANG_CODE" to " ",
            )::get
        )

        assertNull(provider.targetLanguage)
        assertNull(provider.targetLangCode)
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

    @Test
    fun `invalid env initial scan size fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("INITIAL_SCAN_SIZE" to "many")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.initialScanSize }

        assertTrue(e.message!!.contains("INITIAL_SCAN_SIZE"))
    }

    @Test
    fun `invalid env scan transient max retry fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("SCAN_TRANSIENT_MAX_RETRY" to "many")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.scanTransientMaxRetry }

        assertTrue(e.message!!.contains("SCAN_TRANSIENT_MAX_RETRY"))
    }

    @Test
    fun `invalid env scan transient retry delay fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("SCAN_TRANSIENT_RETRY_DELAY" to "soon")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.scanTransientRetryDelay }

        assertTrue(e.message!!.contains("SCAN_TRANSIENT_RETRY_DELAY"))
    }
}
