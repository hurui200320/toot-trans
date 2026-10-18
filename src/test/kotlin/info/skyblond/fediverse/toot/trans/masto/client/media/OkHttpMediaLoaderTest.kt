package info.skyblond.fediverse.toot.trans.masto.client.media

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The wire contract of [OkHttpMediaLoader]: image bytes, media types,
 * size caps and fail-fast errors.
 * */
class OkHttpMediaLoaderTest {
    private lateinit var server: MockWebServer
    private lateinit var loader: OkHttpMediaLoader

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        loader = OkHttpMediaLoader(OkHttpClient())
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `loads image bytes and strips content type parameters`() {
        runTest {
            val bytes = byteArrayOf(1, 2, 3, 4)
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().write(bytes))
                    .setHeader("Content-Type", "IMAGE/PNG; charset=binary")
                    .build()
            )
            val media = loader.load(server.url("/media/1.png").toString())
            assertContentEquals(bytes, media.bytes)
            assertEquals("image/png", media.mediaType)
        }
    }

    @Test
    fun `non-image and missing content types fail fast`() {
        runTest {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body("not an image")
                    .setHeader("Content-Type", "text/html")
                    .build()
            )
            val wrongType = assertFailsWith<IOException> {
                loader.load(server.url("/media/page.html").toString())
            }
            assertTrue(wrongType.message!!.contains("not an image"))

            server.enqueue(MockResponse.Builder().code(200).body("no type").build())
            val missingType = assertFailsWith<IOException> {
                loader.load(server.url("/media/type-less").toString())
            }
            assertTrue(missingType.message!!.contains("not an image"))
        }
    }

    @Test
    fun `non-success responses fail fast`() {
        runTest {
            server.enqueue(MockResponse.Builder().code(404).build())
            val error = assertFailsWith<IOException> {
                loader.load(server.url("/media/missing.png").toString())
            }
            assertTrue(error.message!!.contains("HTTP 404"))
        }
    }

    @Test
    fun `a declared oversized body fails fast before it is read`() {
        runTest {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(Buffer().write(ByteArray(MediaLoader.MAX_IMAGE_BYTES + 1)))
                    .setHeader("Content-Type", "image/png")
                    .build()
            )
            val error = assertFailsWith<IOException> {
                loader.load(server.url("/media/huge.png").toString())
            }
            assertTrue(error.message!!.contains("declares"), error.message)
        }
    }

    @Test
    fun `a chunked oversized body is caught by the read bound`() {
        runTest {
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .chunkedBody(Buffer().write(ByteArray(MediaLoader.MAX_IMAGE_BYTES + 1)), 8192)
                    .setHeader("Content-Type", "image/png")
                    .build()
            )
            val error = assertFailsWith<IOException> {
                loader.load(server.url("/media/chunked.png").toString())
            }
            assertTrue(error.message!!.contains("exceeds"), error.message)
        }
    }

    @Test
    fun `a blank url fails fast`() {
        runTest {
            assertFailsWith<IllegalArgumentException> { loader.load(" ") }
        }
    }
}
