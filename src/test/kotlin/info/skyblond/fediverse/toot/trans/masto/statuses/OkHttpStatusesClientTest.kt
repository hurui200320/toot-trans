package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.MastoApiTestBase
import kotlinx.coroutines.runBlocking
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class OkHttpStatusesClientTest : MastoApiTestBase() {
    private lateinit var client: OkHttpStatusesClient

    // runs after the base class has started the server and built [api]
    @BeforeEach
    fun setUpClient() {
        client = OkHttpStatusesClient(api)
    }

    // body is nullable in mockwebserver3; the requests we assert on
    // always have one, so fail fast if it is missing
    private fun RecordedRequest.bodyUtf8(): String = requireNotNull(body).utf8()

    // trimmed from https://docs.joinmastodon.org/methods/statuses/#get,
    // unknown fields included to prove they are ignored
    private val statusJson = """
        {
          "id": "103254962155278888",
          "created_at": "2019-12-05T11:34:47.196Z",
          "in_reply_to_id": null,
          "in_reply_to_account_id": null,
          "sensitive": false,
          "spoiler_text": "",
          "visibility": "public",
          "language": "en",
          "uri": "https://mastodon.social/users/trwnh/statuses/103254962155278888",
          "content": "<p>test content</p>",
          "reblog": null,
          "application": { "name": "toot-trans", "website": null },
          "account": { "id": "14715", "username": "trwnh", "acct": "trwnh" },
          "media_attachments": [],
          "mentions": [],
          "tags": [],
          "emojis": [],
          "card": null,
          "poll": null,
          "some_future_field": [1, 2, 3]
        }
    """.trimIndent()

    private val fullDraft = CreateStatusRequest(
        status = "translated-toot",
        requestId = "109404611633601269",
        inReplyToId = "109404611633601269",
        language = "en",
        visibility = "unlisted",
        spoilerText = "cw",
        sensitive = true,
    )

    @Test
    fun `getStatus parses the status and ignores unknown fields`() {
        enqueueJson(200, statusJson)

        val status = runBlocking { client.getStatus("103254962155278888") }

        assertEquals("103254962155278888", status.id)
        assertEquals("2019-12-05T11:34:47.196Z", status.createdAt)
        assertNull(status.editedAt)
        assertNull(status.inReplyToId)
        assertNull(status.inReplyToAccountId)
        assertEquals("en", status.language)
        assertEquals("public", status.visibility)
        assertEquals(false, status.sensitive)
        assertEquals(0, status.mediaAttachments.size)
        assertNull(status.quote)
        assertNull(status.card)
        assertNull(status.poll)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/statuses/103254962155278888", recorded.target)
        assertEquals("Bearer test-token", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
    }

    @Test
    fun `getStatus surfaces the server error on 404`() {
        enqueueJson(404, """{"error": "Record not found"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { client.getStatus("404") }
        }

        assertEquals(404, e.httpCode)
        assertEquals("Record not found", e.detail)
    }

    @Test
    fun `getStatus rejects a blank status id`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> { client.getStatus(" ") }
        }
    }

    @Test
    fun `getStatusSource parses the plain text source`() {
        enqueueJson(
            200,
            """
            {
              "id": "108942703571991143",
              "text": "this is a status that will be edited",
              "spoiler_text": "cw"
            }
            """.trimIndent()
        )

        val source = runBlocking { client.getStatusSource("108942703571991143") }

        assertEquals("108942703571991143", source.id)
        assertEquals("this is a status that will be edited", source.text)
        assertEquals("cw", source.spoilerText)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/statuses/108942703571991143/source", recorded.target)
        assertEquals("Bearer test-token", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
    }

    @Test
    fun `getStatusSource surfaces the server error on 404`() {
        // e.g. a status not owned by the token account has no source
        enqueueJson(404, """{"error": "Record not found"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { client.getStatusSource("404") }
        }

        assertEquals(404, e.httpCode)
        assertEquals("Record not found", e.detail)
    }

    @Test
    fun `getStatusSource rejects a blank status id`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> { client.getStatusSource("") }
        }
    }

    @Test
    fun `createStatus posts all non-null draft fields`() {
        enqueueJson(200, statusJson)

        val status = runBlocking { client.createStatus(fullDraft) }

        assertEquals("103254962155278888", status.id)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/statuses", recorded.target)
        assertEquals("Bearer test-token", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
        assertEquals("application/x-www-form-urlencoded", recorded.headers["Content-Type"])
        // requestId is local-only: it must not appear on the wire
        assertEquals(
            "status=translated-toot&in_reply_to_id=109404611633601269" +
                "&language=en&visibility=unlisted&spoiler_text=cw&sensitive=true",
            recorded.bodyUtf8()
        )
    }

    @Test
    fun `createStatus sends the derived idempotency key`() {
        enqueueJson(200, statusJson)

        runBlocking { client.createStatus(fullDraft) }

        val recorded = server.takeRequest()
        // golden value: sha256 of the canonical string documented on
        // StatusesClient.createStatus. A fixed value instead of a
        // recomputation, so the derivation cannot change unnoticed.
        assertEquals(
            "5af153cda9f6a5a8a7399e25b1a2bf7705326fe7374c9efe747a3531f4c29f25",
            recorded.headers["Idempotency-Key"]
        )
    }

    @Test
    fun `createStatus idempotency keys differ for different request ids`() {
        enqueueJson(200, statusJson)
        enqueueJson(200, statusJson)

        runBlocking {
            client.createStatus(fullDraft.copy(requestId = "1111"))
            client.createStatus(fullDraft.copy(requestId = "2222"))
        }

        val first = server.takeRequest()
        val second = server.takeRequest()
        // same content, but different logical requests: the server-side
        // dedup of one must not swallow the other
        assertEquals(
            "status=translated-toot&in_reply_to_id=109404611633601269" +
                "&language=en&visibility=unlisted&spoiler_text=cw&sensitive=true",
            first.bodyUtf8()
        )
        assertEquals(first.bodyUtf8(), second.bodyUtf8())
        assertNotEquals(
            first.headers["Idempotency-Key"],
            second.headers["Idempotency-Key"]
        )
    }

    @Test
    fun `createStatus idempotency keys differ for ambiguous raw text`() {
        // status "x&language=en" vs status "x" with language "en":
        // concatenated raw they are the same string, so encoding the
        // values is what keeps the two keys apart
        enqueueJson(200, statusJson)
        enqueueJson(200, statusJson)

        runBlocking {
            client.createStatus(
                CreateStatusRequest(status = "x&language=en", requestId = "rid")
            )
            client.createStatus(
                CreateStatusRequest(status = "x", requestId = "rid", language = "en")
            )
        }

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNotEquals(
            first.headers["Idempotency-Key"],
            second.headers["Idempotency-Key"]
        )
    }

    @Test
    fun `createStatus form-encodes non-ASCII and special characters`() {
        // the posted text is a translation, i.e. non-ASCII in practice;
        // pin the exact wire encoding of the form body
        enqueueJson(200, statusJson)

        runBlocking {
            client.createStatus(
                CreateStatusRequest(status = "こんにちは & 你好=世界", requestId = "1")
            )
        }

        val recorded = server.takeRequest()
        assertEquals(
            "status=%E3%81%93%E3%82%93%E3%81%AB%E3%81%A1%E3%81%AF+%26+%E4%BD%A0%E5%A5%BD%3D%E4%B8%96%E7%95%8C",
            recorded.bodyUtf8()
        )
    }

    @Test
    fun `createStatus omits null draft fields`() {
        enqueueJson(200, statusJson)

        runBlocking {
            client.createStatus(CreateStatusRequest(status = "translated-toot", requestId = "123"))
        }

        val recorded = server.takeRequest()
        assertEquals("status=translated-toot", recorded.bodyUtf8())
    }

    @Test
    fun `createStatus surfaces the server error on 422`() {
        enqueueJson(422, """{"error": "Validation failed: Text can't be blank"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> {
                client.createStatus(CreateStatusRequest(status = "x", requestId = "1"))
            }
        }

        assertEquals(422, e.httpCode)
        assertEquals("Validation failed: Text can't be blank", e.detail)
    }

    @Test
    fun `createStatus surfaces the server error on 401`() {
        enqueueJson(401, """{"error": "The access token is invalid"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> {
                client.createStatus(CreateStatusRequest(status = "x", requestId = "1"))
            }
        }

        assertEquals(401, e.httpCode)
        assertEquals("The access token is invalid", e.detail)
    }

    @Test
    fun `createStatus rejects a blank status`() {
        val e = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                client.createStatus(CreateStatusRequest(status = " ", requestId = "1"))
            }
        }

        assertEquals("status must not be blank", e.message)
    }

    @Test
    fun `createStatus rejects a blank request id`() {
        val e = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                client.createStatus(CreateStatusRequest(status = "x", requestId = ""))
            }
        }

        assertEquals("requestId must not be blank", e.message)
    }

    @Test
    fun `createStatus rejects an unknown visibility`() {
        val e = runBlocking {
            assertFailsWith<IllegalArgumentException> {
                client.createStatus(
                    CreateStatusRequest(status = "x", requestId = "1", visibility = "followers")
                )
            }
        }

        assertEquals(
            "visibility must be one of [public, unlisted, private, direct], got `followers`",
            e.message
        )
    }

    @Test
    fun `createStatus rejects blank optional fields`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                client.createStatus(
                    CreateStatusRequest(status = "x", requestId = "1", inReplyToId = " ")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                client.createStatus(
                    CreateStatusRequest(status = "x", requestId = "1", language = "")
                )
            }
            assertFailsWith<IllegalArgumentException> {
                client.createStatus(
                    CreateStatusRequest(status = "x", requestId = "1", spoilerText = "")
                )
            }
        }
    }

    @Test
    fun `deleteStatus sends DELETE and ignores the response body`() {
        // the response carries the deleted Status incl. its source text;
        // trimmed from https://docs.joinmastodon.org/methods/statuses/#delete
        enqueueJson(
            200,
            """
            {
              "id": "103254193998341330",
              "created_at": "2019-12-05T08:19:26.052Z",
              "in_reply_to_id": null,
              "in_reply_to_account_id": null,
              "sensitive": false,
              "spoiler_text": "",
              "visibility": "public",
              "language": "en",
              "text": "test",
              "media_attachments": []
            }
            """.trimIndent()
        )

        runBlocking { client.deleteStatus("103254193998341330") }

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/v1/statuses/103254193998341330", recorded.target)
        assertEquals("Bearer test-token", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
    }

    @Test
    fun `deleteStatus surfaces the server error on 404`() {
        // e.g. deleting a status owned by someone else
        enqueueJson(404, """{"error": "Record not found"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { client.deleteStatus("404") }
        }

        assertEquals(404, e.httpCode)
        assertEquals("Record not found", e.detail)
    }

    @Test
    fun `deleteStatus rejects a blank status id`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> { client.deleteStatus("") }
        }
    }
}
