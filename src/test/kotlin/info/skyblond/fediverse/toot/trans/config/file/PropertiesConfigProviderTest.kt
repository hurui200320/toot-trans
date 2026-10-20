package info.skyblond.fediverse.toot.trans.config.file

import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PropertiesConfigProviderTest {
    @Test
    fun `reads values from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            """
            mastoHost=https://m.skyblond.info/
            accessToken=some-token
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

        assertEquals("https://m.skyblond.info/", provider.mastoHost)
        assertEquals("some-token", provider.accessToken)
    }

    @Test
    fun `returns null for keys missing from the file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://m.skyblond.info/\n")

        val provider = PropertiesConfigProvider(file)

        assertEquals("https://m.skyblond.info/", provider.mastoHost)
        assertNull(provider.accessToken)
    }

    @Test
    fun `missing file falls back with nulls`(@TempDir tempDir: File) {
        val provider = PropertiesConfigProvider(tempDir.resolve("does-not-exist.properties"))

        assertNull(provider.mastoHost)
        assertNull(provider.accessToken)
    }

    @Test
    fun `path pointing to a directory fails fast`(@TempDir tempDir: File) {
        val e = assertFailsWith<IllegalArgumentException> {
            PropertiesConfigProvider(tempDir)
        }
        assertEquals("Properties file `$tempDir` is not a file", e.message)
    }

    @Test
    fun `reads non-ASCII values as UTF-8`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("accessToken=トークン\n", Charsets.UTF_8)

        val provider = PropertiesConfigProvider(file)

        assertEquals("トークン", provider.accessToken)
    }

    @Test
    fun `file is read only once at construction`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://before.example/\n")
        val provider = PropertiesConfigProvider(file)

        // later changes to the file should not be visible to the provider
        file.writeText("mastoHost=https://after.example/\n")

        assertEquals("https://before.example/", provider.mastoHost)
    }

    @Test
    fun `reads pool configs from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            """
            poolBatchWindow=500ms
            poolCacheTtl=5m
            poolMaxCacheEntries=200
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

        assertEquals(500.milliseconds, provider.poolBatchWindow)
        assertEquals(5.minutes, provider.poolCacheTtl)
        assertEquals(200, provider.poolMaxCacheEntries)
    }

    @Test
    fun `reads backoff configs from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            """
            backoffResetMargin=1s
            backoffFallbackDelay=30s
            backoffMaxWaitDuration=2h
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

        assertEquals(1.seconds, provider.backoffResetMargin)
        assertEquals(30.seconds, provider.backoffFallbackDelay)
        assertEquals(2.hours, provider.backoffMaxWaitDuration)
    }

    @Test
    fun `returns null for pool keys missing from the file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://m.skyblond.info/\n")

        val provider = PropertiesConfigProvider(file)

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
    }

    @Test
    fun `reads the initial scan size from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("initialScanSize=20\n")

        val provider = PropertiesConfigProvider(file)

        assertEquals(20, provider.initialScanSize)
    }

    @Test
    fun `reads the scan interval from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("scanInterval=30s\n")

        assertEquals(30.seconds, PropertiesConfigProvider(file).scanInterval)
        file.writeText("scanInterval=   \n")
        assertNull(PropertiesConfigProvider(file).scanInterval)
    }

    @Test
    fun `malformed scan interval names properties key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("scanInterval=soon\n")
        val e = assertFailsWith<IllegalArgumentException> { PropertiesConfigProvider(file).scanInterval }
        assertTrue(e.message!!.contains("scanInterval"))
    }

    @Test
    fun `reads cooldown from properties including zero and blank`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("translationCooldown=2m\n")
        assertEquals(2.minutes, PropertiesConfigProvider(file).translationCooldown)
        file.writeText("translationCooldown=0s\n")
        assertEquals(0.seconds, PropertiesConfigProvider(file).translationCooldown)
        file.writeText("translationCooldown=   \n")
        assertNull(PropertiesConfigProvider(file).translationCooldown)
    }

    @Test
    fun `malformed cooldown names properties key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("translationCooldown=soon\n")
        val e = assertFailsWith<IllegalArgumentException> { PropertiesConfigProvider(file).translationCooldown }
        assertTrue(e.message!!.contains("translationCooldown"))
    }

    @Test
    fun `reads translation max age from properties including zero and blank`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("translationMaxAge=12h\n")
        assertEquals(12.hours, PropertiesConfigProvider(file).translationMaxAge)
        file.writeText("translationMaxAge=0s\n")
        assertEquals(0.seconds, PropertiesConfigProvider(file).translationMaxAge)
        file.writeText("translationMaxAge=   \n")
        assertNull(PropertiesConfigProvider(file).translationMaxAge)
    }

    @Test
    fun `malformed translation max age names properties key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("translationMaxAge=old\n")
        val e = assertFailsWith<IllegalArgumentException> { PropertiesConfigProvider(file).translationMaxAge }
        assertTrue(e.message!!.contains("translationMaxAge"))
    }

    @Test
    fun `reads reply chain depth from properties including zero and blank`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("replyChainDepth=5\n")
        assertEquals(5, PropertiesConfigProvider(file).replyChainDepth)
        file.writeText("replyChainDepth=0\n")
        assertEquals(0, PropertiesConfigProvider(file).replyChainDepth)
        file.writeText("replyChainDepth=   \n")
        assertNull(PropertiesConfigProvider(file).replyChainDepth)
    }

    @Test
    fun `malformed reply chain depth names properties key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("replyChainDepth=deep\n")
        val e = assertFailsWith<IllegalArgumentException> { PropertiesConfigProvider(file).replyChainDepth }
        assertTrue(e.message!!.contains("replyChainDepth"))
    }

    @Test
    fun `reads transient configs from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            """
            transientMaxRetry=5
            transientRetryDelay=30s
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

        assertEquals(5, provider.transientMaxRetry)
        assertEquals(30.seconds, provider.transientRetryDelay)
    }

    @Test
    fun `invalid initial scan size fails fast naming the key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("initialScanSize=many\n")
        val provider = PropertiesConfigProvider(file)

        val e = assertFailsWith<IllegalArgumentException> { provider.initialScanSize }

        assertTrue(e.message!!.contains("initialScanSize"))
    }

    @Test
    fun `invalid transient retry delay fails fast naming the key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("transientRetryDelay=soon\n")
        val provider = PropertiesConfigProvider(file)

        val e = assertFailsWith<IllegalArgumentException> { provider.transientRetryDelay }

        assertTrue(e.message!!.contains("transientRetryDelay"))
    }

    @Test
    fun `reads target lang configs from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            """
            targetLanguage=Simplified Chinese
            targetLangCode=zh-CN
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

        assertEquals("Simplified Chinese", provider.targetLanguage)
        // a regional tag, taken as-is like any other value
        assertEquals("zh-CN", provider.targetLangCode)
    }

    @Test
    fun `returns null for target lang keys missing from the file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://m.skyblond.info/\n")

        val provider = PropertiesConfigProvider(file)

        assertNull(provider.targetLanguage)
        assertNull(provider.targetLangCode)
    }

    @Test
    fun `blank target lang values are treated as not configured`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("targetLanguage=\ntargetLangCode=   \n")

        val provider = PropertiesConfigProvider(file)

        assertNull(provider.targetLanguage)
        assertNull(provider.targetLangCode)
    }

    @Test
    fun `blank pool values are treated as not configured`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("poolBatchWindow=\n")

        val provider = PropertiesConfigProvider(file)

        assertNull(provider.poolBatchWindow)
    }

    @Test
    fun `invalid pool duration fails fast naming the key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("poolBatchWindow=soon\n")
        val provider = PropertiesConfigProvider(file)

        val e = assertFailsWith<IllegalArgumentException> { provider.poolBatchWindow }

        assertTrue(e.message!!.contains("poolBatchWindow"))
    }

    @Test
    fun `invalid pool integer fails fast naming the key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("poolMaxCacheEntries=lots\n")
        val provider = PropertiesConfigProvider(file)

        val e = assertFailsWith<IllegalArgumentException> { provider.poolMaxCacheEntries }

        assertTrue(e.message!!.contains("poolMaxCacheEntries"))
    }

    @Test
    fun `reads LLM configs from the properties file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            """
            llmBaseUrl=https://api.example.com/v1
            llmApiKey=some-key
            llmConnectTimeout=5s
            llmReadTimeout=15m
            llmWriteTimeout=3m
            llmModel=gpt-5-mini
            llmMaxCompletionTokens=4096
            llmReasoningEffort=medium
            llmReqExtra={"temperature": 0.2}
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

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
    fun `returns null for LLM keys missing from the file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://m.skyblond.info/\n")

        val provider = PropertiesConfigProvider(file)

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
    fun `invalid LLM values fail fast naming the key`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("llmMaxCompletionTokens=many\n")
        val badInt = assertFailsWith<IllegalArgumentException> {
            PropertiesConfigProvider(file).llmMaxCompletionTokens
        }
        assertTrue(badInt.message!!.contains("llmMaxCompletionTokens"))
        file.writeText("llmReadTimeout=fast\n")
        val badDuration = assertFailsWith<IllegalArgumentException> {
            PropertiesConfigProvider(file).llmReadTimeout
        }
        assertTrue(badDuration.message!!.contains("llmReadTimeout"))
        file.writeText("llmReasoningEffort=smart\n")
        val badEffort = assertFailsWith<IllegalArgumentException> {
            PropertiesConfigProvider(file).llmReasoningEffort
        }
        assertTrue(badEffort.message!!.contains("llmReasoningEffort"))
        file.writeText("llmReqExtra=[1, 2]\n")
        val badExtra = assertFailsWith<IllegalArgumentException> {
            PropertiesConfigProvider(file).llmReqExtra
        }
        assertTrue(badExtra.message!!.contains("llmReqExtra"))
    }
}
