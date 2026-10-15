package info.skyblond.fediverse.toot.trans.config.file

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
            poolResetMargin=1s
            poolFallbackDelay=30s
            poolMaxWaitDuration=2h
            """.trimIndent()
        )

        val provider = PropertiesConfigProvider(file)

        assertEquals(500.milliseconds, provider.poolBatchWindow)
        assertEquals(5.minutes, provider.poolCacheTtl)
        assertEquals(200, provider.poolMaxCacheEntries)
        assertEquals(1.seconds, provider.poolResetMargin)
        assertEquals(30.seconds, provider.poolFallbackDelay)
        assertEquals(2.hours, provider.poolMaxWaitDuration)
    }

    @Test
    fun `returns null for pool keys missing from the file`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://m.skyblond.info/\n")

        val provider = PropertiesConfigProvider(file)

        assertNull(provider.poolBatchWindow)
        assertNull(provider.poolMaxCacheEntries)
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
}
