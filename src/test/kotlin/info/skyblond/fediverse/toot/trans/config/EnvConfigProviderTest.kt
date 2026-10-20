package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import kotlinx.serialization.json.jsonPrimitive
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
        assertNull(provider.scanInterval)
        assertNull(provider.transientMaxRetry)
        assertNull(provider.transientRetryDelay)
        assertNull(provider.translationCooldown)
        assertNull(provider.translationMaxAge)
        assertNull(provider.replyChainDepth)
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
    fun `reads the scan interval from the environment`() {
        assertEquals(30.seconds, EnvConfigProvider(mapOf("SCAN_INTERVAL" to "30s")::get).scanInterval)
    }

    @Test
    fun `blank scan interval is unset and malformed names variable`() {
        assertNull(EnvConfigProvider(mapOf("SCAN_INTERVAL" to " ")::get).scanInterval)
        val e = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("SCAN_INTERVAL" to "soon")::get).scanInterval
        }
        assertTrue(e.message!!.contains("SCAN_INTERVAL"))
    }

    @Test
    fun `reads cooldown including zero from environment`() {
        val provider = EnvConfigProvider(mapOf("TRANSLATION_COOLDOWN" to "2m")::get)
        assertEquals(2.minutes, provider.translationCooldown)
        assertEquals(0.seconds, EnvConfigProvider(mapOf("TRANSLATION_COOLDOWN" to "0s")::get).translationCooldown)
    }

    @Test
    fun `blank cooldown is unset and malformed cooldown names variable`() {
        assertNull(EnvConfigProvider(mapOf("TRANSLATION_COOLDOWN" to " ")::get).translationCooldown)
        val e = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("TRANSLATION_COOLDOWN" to "soon")::get).translationCooldown
        }
        assertTrue(e.message!!.contains("TRANSLATION_COOLDOWN"))
    }

    @Test
    fun `reads translation max age including zero from the environment`() {
        assertEquals(12.hours, EnvConfigProvider(mapOf("TRANSLATION_MAX_AGE" to "12h")::get).translationMaxAge)
        assertEquals(0.seconds, EnvConfigProvider(mapOf("TRANSLATION_MAX_AGE" to "0s")::get).translationMaxAge)
    }

    @Test
    fun `blank translation max age is unset and malformed names variable`() {
        assertNull(EnvConfigProvider(mapOf("TRANSLATION_MAX_AGE" to " ")::get).translationMaxAge)
        val e = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("TRANSLATION_MAX_AGE" to "old")::get).translationMaxAge
        }
        assertTrue(e.message!!.contains("TRANSLATION_MAX_AGE"))
    }

    @Test
    fun `reads reply chain depth including zero from the environment`() {
        assertEquals(5, EnvConfigProvider(mapOf("REPLY_CHAIN_DEPTH" to "5")::get).replyChainDepth)
        assertEquals(0, EnvConfigProvider(mapOf("REPLY_CHAIN_DEPTH" to "0")::get).replyChainDepth)
    }

    @Test
    fun `blank reply chain depth is unset and malformed names variable`() {
        assertNull(EnvConfigProvider(mapOf("REPLY_CHAIN_DEPTH" to " ")::get).replyChainDepth)
        val e = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("REPLY_CHAIN_DEPTH" to "deep")::get).replyChainDepth
        }
        assertTrue(e.message!!.contains("REPLY_CHAIN_DEPTH"))
    }

    @Test
    fun `reads transient configs from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "TRANSIENT_MAX_RETRY" to "5",
                "TRANSIENT_RETRY_DELAY" to "30s",
            )::get
        )

        assertEquals(5, provider.transientMaxRetry)
        assertEquals(30.seconds, provider.transientRetryDelay)
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
    fun `invalid env transient max retry fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("TRANSIENT_MAX_RETRY" to "many")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.transientMaxRetry }

        assertTrue(e.message!!.contains("TRANSIENT_MAX_RETRY"))
    }

    @Test
    fun `invalid env transient retry delay fails fast naming the variable`() {
        val provider = EnvConfigProvider(mapOf("TRANSIENT_RETRY_DELAY" to "soon")::get)

        val e = assertFailsWith<IllegalArgumentException> { provider.transientRetryDelay }

        assertTrue(e.message!!.contains("TRANSIENT_RETRY_DELAY"))
    }

    @Test
    fun `reads LLM configs from the environment`() {
        val provider = EnvConfigProvider(
            mapOf(
                "LLM_BASE_URL" to "https://api.example.com/v1",
                "LLM_API_KEY" to "some-key",
                "LLM_CONNECT_TIMEOUT" to "5s",
                "LLM_READ_TIMEOUT" to "15m",
                "LLM_WRITE_TIMEOUT" to "3m",
                "LLM_MODEL" to "gpt-5-mini",
                "LLM_MAX_COMPLETION_TOKENS" to "4096",
                "LLM_REASONING_EFFORT" to "medium",
                "LLM_REQ_EXTRA" to """{"temperature": 0.2}""",
            )::get
        )

        assertEquals("https://api.example.com/v1", provider.llmBaseUrl)
        assertEquals("some-key", provider.llmApiKey)
        assertEquals(5.seconds, provider.llmConnectTimeout)
        assertEquals(15.minutes, provider.llmReadTimeout)
        assertEquals(3.minutes, provider.llmWriteTimeout)
        assertEquals("gpt-5-mini", provider.llmModel)
        assertEquals(4096, provider.llmMaxCompletionTokens)
        assertEquals(ReasoningEffort.MEDIUM, provider.llmReasoningEffort)
        assertEquals("0.2", provider.llmReqExtra?.get("temperature")?.jsonPrimitive?.content)
    }

    @Test
    fun `returns null for missing LLM env vars`() {
        val provider = EnvConfigProvider(emptyMap<String, String>()::get)

        assertNull(provider.llmBaseUrl)
        assertNull(provider.llmApiKey)
        assertNull(provider.llmConnectTimeout)
        assertNull(provider.llmReadTimeout)
        assertNull(provider.llmWriteTimeout)
        assertNull(provider.llmModel)
        assertNull(provider.llmMaxCompletionTokens)
        assertNull(provider.llmReasoningEffort)
        assertNull(provider.llmReqExtra)
    }

    @Test
    fun `invalid LLM env values fail fast naming the variable`() {
        val badInt = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("LLM_MAX_COMPLETION_TOKENS" to "many")::get).llmMaxCompletionTokens
        }
        assertTrue(badInt.message!!.contains("LLM_MAX_COMPLETION_TOKENS"))
        val badDuration = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("LLM_READ_TIMEOUT" to "fast")::get).llmReadTimeout
        }
        assertTrue(badDuration.message!!.contains("LLM_READ_TIMEOUT"))
        val badEffort = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("LLM_REASONING_EFFORT" to "smart")::get).llmReasoningEffort
        }
        assertTrue(badEffort.message!!.contains("LLM_REASONING_EFFORT"))
        val badExtra = assertFailsWith<IllegalArgumentException> {
            EnvConfigProvider(mapOf("LLM_REQ_EXTRA" to "{broken json")::get).llmReqExtra
        }
        assertTrue(badExtra.message!!.contains("LLM_REQ_EXTRA"))
    }
}
