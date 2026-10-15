package info.skyblond.fediverse.toot.trans.masto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import mockwebserver3.MockResponse

class MastoApiClientTest : MastoApiTestBase() {
    // the transport tests below are endpoint-agnostic: they all issue
    // the same GET, and the mocked response decides the outcome
    private suspend fun issueGet(): String = api.get(
        path = listOf("api", "v1", "statuses"),
        deserializer = String.serializer(),
    )

    @Test
    fun `rejects a base URL that is not a URL`() {
        val e = assertFailsWith<IllegalArgumentException> {
            MastoApiClient("mastodon.social", "token")
        }
        assertEquals("Base URL `mastodon.social` is not a valid URL", e.message)
    }

    @Test
    fun `rejects a non-http scheme`() {
        // HttpUrl only accepts http/https, so this fails as "not a valid URL"
        assertFailsWith<IllegalArgumentException> {
            MastoApiClient("ftp://mastodon.social/", "token")
        }
    }

    @Test
    fun `rejects a base URL with a path`() {
        val e = assertFailsWith<IllegalArgumentException> {
            MastoApiClient("https://mastodon.social/masto/", "token")
        }
        assertEquals(
            "Base URL `https://mastodon.social/masto/` must not contain a path, query or fragment",
            e.message
        )
    }

    @Test
    fun `accepts a plain host`() {
        // no trailing slash
        MastoApiClient("https://mastodon.social", "token")
        // the trailing-slash http://host:port form the test server hands out
        MastoApiClient("http://localhost:8080/", "token")
    }

    @Test
    fun `rejects a base URL with a query`() {
        val e = assertFailsWith<IllegalArgumentException> {
            MastoApiClient("https://mastodon.social/?a=b", "token")
        }
        assertEquals(
            "Base URL `https://mastodon.social/?a=b` must not contain a path, query or fragment",
            e.message
        )
    }

    @Test
    fun `rejects a base URL with a fragment`() {
        val e = assertFailsWith<IllegalArgumentException> {
            MastoApiClient("https://mastodon.social/#api", "token")
        }
        assertEquals(
            "Base URL `https://mastodon.social/#api` must not contain a path, query or fragment",
            e.message
        )
    }

    @Test
    fun `post rejects an Authorization header in headers`() {
        // lowercase on purpose: HTTP header names are case-insensitive
        val e = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                api.post(
                    path = listOf("api", "v1", "statuses"),
                    deserializer = String.serializer(),
                    headers = mapOf("authorization" to "Bearer evil"),
                )
            }
        }

        // the guard fires before any request goes out
        assertEquals(
            "Authorization is owned by the access token and must not be passed in headers",
            e.message
        )
    }

    @Test
    fun `non-JSON error bodies still fail with the status code`() {
        server.enqueue(
            MockResponse.Builder()
                .code(502)
                .body("<html>Bad Gateway</html>")
                .build()
        )

        val e = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }

        assertEquals(502, e.httpCode)
        assertEquals("<unexpected error body: <html>Bad Gateway</html>>", e.detail)
    }

    @Test
    fun `oversized error bodies are capped instead of fully buffered`() {
        // a pathological server body, way over the read cap
        server.enqueue(
            MockResponse.Builder()
                .code(502)
                .body("<html>" + "x".repeat(10_000) + "</html>")
                .build()
        )

        val e = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }

        // the capped read cannot be valid JSON, so the snippet fallback
        // kicks in, truncated to 200 chars like any other snippet
        assertEquals(502, e.httpCode)
        assertEquals(
            "<unexpected error body: <html>" + "x".repeat(194) + ">",
            e.detail
        )
    }

    @Test
    fun `429 responses expose the rate limit reset timestamp`() {
        server.enqueue(
            MockResponse.Builder()
                .code(429)
                .setHeader("X-RateLimit-Reset", "2026-10-15T00:05:00.000000Z")
                .body("""{"error": "Too many requests"}""")
                .build()
        )

        val e = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }

        assertEquals(429, e.httpCode)
        assertEquals("Too many requests", e.detail)
        assertEquals("2026-10-15T00:05:00.000000Z", e.rateLimitReset)
    }

    @Test
    fun `non-429 errors carry no rate limit reset`() {
        enqueueJson(401, """{"error": "The access token is invalid"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }

        assertEquals(401, e.httpCode)
        assertNull(e.rateLimitReset)
    }

    @Test
    fun `429 without the reset header carries no rate limit reset`() {
        enqueueJson(429, """{"error": "Too many requests"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }

        assertEquals(429, e.httpCode)
        assertNull(e.rateLimitReset)
    }

    @Test
    fun `empty error bodies still fail with the status code`() {
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .build()
        )

        val e = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }

        assertEquals(500, e.httpCode)
        assertEquals("<no error body>", e.detail)
    }

    @Test
    fun `error bodies without a message surface the body snippet`() {
        // a parseable Error entity with the message missing or null
        enqueueJson(502, "{}")

        val first = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }
        assertEquals(502, first.httpCode)
        assertEquals("<error body without message: {}>", first.detail)

        enqueueJson(502, """{"error": null}""")

        val second = runBlocking {
            assertFailsWith<MastoApiException> { issueGet() }
        }
        assertEquals("""<error body without message: {"error": null}>""", second.detail)
    }

    @Test
    fun `malformed JSON bodies fail fast`() {
        enqueueJson(200, """{"id": "123",""")

        runBlocking {
            assertFailsWith<SerializationException> { issueGet() }
        }
    }
}
