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

class ConfigValueParsingTest {
    @Test
    fun `parses durations with units`() {
        assertEquals(500.milliseconds, parseDurationConfig("backoffResetMargin", "500ms"))
        assertEquals(1.seconds, parseDurationConfig("poolBatchWindow", "1s"))
        assertEquals(2.minutes, parseDurationConfig("poolCacheTtl", "2m"))
        assertEquals(24.hours, parseDurationConfig("backoffMaxWaitDuration", "24h"))
    }

    @Test
    fun `parses compound and ISO-8601 durations`() {
        assertEquals(90.seconds, parseDurationConfig("backoffFallbackDelay", "1m 30s"))
        assertEquals(90.seconds, parseDurationConfig("backoffFallbackDelay", "PT1M30S"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals(1.seconds, parseDurationConfig("poolBatchWindow", " 1s "))
        assertEquals(100, parseIntConfig("poolMaxCacheEntries", " 100 "))
        assertEquals("English", parseStringConfig(" English "))
        assertEquals("zh-CN", parseStringConfig(" zh-CN "))
    }

    @Test
    fun `null and blank mean not configured`() {
        assertNull(parseDurationConfig("poolBatchWindow", null))
        assertNull(parseDurationConfig("poolBatchWindow", ""))
        assertNull(parseDurationConfig("poolBatchWindow", "   "))
        assertNull(parseIntConfig("poolMaxCacheEntries", null))
        assertNull(parseIntConfig("poolMaxCacheEntries", ""))
        assertNull(parseStringConfig(null))
        assertNull(parseStringConfig(""))
        assertNull(parseStringConfig("   "))
    }

    @Test
    fun `invalid duration fails fast naming the config`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseDurationConfig("poolBatchWindow", "fast")
        }
        assertTrue(e.message!!.contains("poolBatchWindow"))
        assertTrue(e.message!!.contains("fast"))
    }

    @Test
    fun `unitless numbers are not durations`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseDurationConfig("poolCacheTtl", "1000")
        }
        assertTrue(e.message!!.contains("poolCacheTtl"))
    }

    @Test
    fun `parses integers`() {
        assertEquals(1_000, parseIntConfig("poolMaxCacheEntries", "1000"))
        assertEquals(1, parseIntConfig("poolMaxCacheEntries", "1"))
    }

    @Test
    fun `invalid integer fails fast naming the config`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseIntConfig("poolMaxCacheEntries", "lots")
        }
        assertTrue(e.message!!.contains("poolMaxCacheEntries"))
        assertTrue(e.message!!.contains("lots"))
    }

    @Test
    fun `parses plain strings`() {
        assertEquals("English", parseStringConfig("English"))
        assertEquals("Simplified Chinese", parseStringConfig("Simplified Chinese"))
        // regional language tags pass through unchanged, see
        // [ConfigProvider.targetLangCode]'s KDoc for why they are valid
        assertEquals("zh-CN", parseStringConfig("zh-CN"))
        assertEquals("en", parseStringConfig("en"))
    }

    @Test
    fun `parses enum names case-insensitively`() {
        assertEquals(ReasoningEffort.MEDIUM, parseEnumConfig<ReasoningEffort>("llmReasoningEffort", "medium"))
        assertEquals(ReasoningEffort.MEDIUM, parseEnumConfig<ReasoningEffort>("llmReasoningEffort", "MEDIUM"))
        assertEquals(ReasoningEffort.NONE, parseEnumConfig<ReasoningEffort>("llmReasoningEffort", " none "))
    }

    @Test
    fun `null and blank mean not configured for enums and JSON objects`() {
        assertNull(parseEnumConfig<ReasoningEffort>("llmReasoningEffort", null))
        assertNull(parseEnumConfig<ReasoningEffort>("llmReasoningEffort", "   "))
        assertNull(parseJsonObjectConfig("llmReqExtra", null))
        assertNull(parseJsonObjectConfig("llmReqExtra", "   "))
    }

    @Test
    fun `invalid enum value fails fast naming the config and accepted names`() {
        val e = assertFailsWith<IllegalArgumentException> {
            parseEnumConfig<ReasoningEffort>("llmReasoningEffort", "smart")
        }
        assertTrue(e.message!!.contains("llmReasoningEffort"))
        assertTrue(e.message!!.contains("smart"))
        assertTrue(e.message!!.contains("medium"))
    }

    @Test
    fun `parses JSON objects`() {
        val parsed = parseJsonObjectConfig("llmReqExtra", """{"temperature": 0.2, "enable_thinking": false}""")
        assertEquals("0.2", parsed?.get("temperature")?.jsonPrimitive?.content)
        assertEquals("false", parsed?.get("enable_thinking")?.jsonPrimitive?.content)
        assertEquals("{}", parseJsonObjectConfig("llmReqExtra", " {} ")?.toString())
    }

    @Test
    fun `malformed and non-object JSON fails fast naming the config`() {
        val malformed = assertFailsWith<IllegalArgumentException> {
            parseJsonObjectConfig("llmReqExtra", """{"temperature": }""")
        }
        assertTrue(malformed.message!!.contains("llmReqExtra"))
        val nonObject = assertFailsWith<IllegalArgumentException> {
            parseJsonObjectConfig("llmReqExtra", "[1, 2]")
        }
        assertTrue(nonObject.message!!.contains("llmReqExtra"))
    }
}
