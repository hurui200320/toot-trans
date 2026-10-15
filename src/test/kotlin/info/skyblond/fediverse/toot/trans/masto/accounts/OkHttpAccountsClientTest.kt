package info.skyblond.fediverse.toot.trans.masto.accounts

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.MastoApiTestBase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class OkHttpAccountsClientTest : MastoApiTestBase() {
    private lateinit var client: OkHttpAccountsClient

    // runs after the base class has started the server and built [api]
    @BeforeEach
    fun setUpClient() {
        client = OkHttpAccountsClient(api)
    }

    @Test
    fun `verifyCredentials returns the account and sends the token`() {
        // trimmed from https://docs.joinmastodon.org/methods/accounts/#verify_credentials,
        // unknown fields included to prove they are ignored
        enqueueJson(
            200,
            """
            {
              "id": "14715",
              "username": "trwnh",
              "acct": "trwnh",
              "display_name": "infinite love",
              "locked": false,
              "bot": false,
              "created_at": "2016-11-24T10:02:12.085Z",
              "note": "<p>some bio</p>",
              "url": "https://mastodon.social/@trwnh",
              "source": {
                "privacy": "public",
                "sensitive": false,
                "language": "",
                "note": "some bio",
                "fields": [],
                "follow_requests_count": 0
              },
              "emojis": [],
              "fields": [],
              "role": {
                "id": "-99",
                "name": "",
                "permissions": "65536",
                "color": "",
                "highlighted": false
              }
            }
            """.trimIndent()
        )

        val account = runBlocking { client.verifyCredentials() }

        assertEquals("14715", account.id)
        assertEquals("trwnh", account.username)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/accounts/verify_credentials", recorded.target)
        assertEquals("Bearer test-token", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
    }

    @Test
    fun `verifyCredentials surfaces the server error on 401`() {
        enqueueJson(401, """{"error": "The access token is invalid"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { client.verifyCredentials() }
        }

        assertEquals(401, e.httpCode)
        assertEquals("The access token is invalid", e.detail)
    }

    @Test
    fun `getStatuses parses statuses and ignores unknown fields`() {
        // trimmed from https://docs.joinmastodon.org/entities/Status,
        // unknown fields included to prove they are ignored
        enqueueJson(
            200,
            """
            [
              {
                "id": "108880211901672326",
                "created_at": "2022-08-24T22:29:46.493Z",
                "in_reply_to_id": null,
                "in_reply_to_account_id": null,
                "sensitive": false,
                "spoiler_text": "",
                "visibility": "public",
                "language": "en",
                "uri": "https://mastodon.social/users/Gargron/statuses/108880211901672326",
                "content": "<p>hello world</p>",
                "reblog": null,
                "application": { "name": "Web", "website": null },
                "account": { "id": "1", "username": "Gargron", "acct": "Gargron" },
                "media_attachments": [
                  {
                    "id": "22345792",
                    "type": "image",
                    "url": "https://files.mastodon.social/media_attachments/022/345/792/original/57859aede991da25.jpeg",
                    "preview_url": "https://files.mastodon.social/media_attachments/022/345/792/small/57859aede991da25.jpeg",
                    "remote_url": null,
                    "meta": {
                      "original": { "width": 640, "height": 480, "size": "640x480", "aspect": 1.3333333333333333 }
                    },
                    "description": "test media description",
                    "blurhash": "UFBWY:8_0Jxv4mx]t8t64.%M-:IUWGWAt6M}"
                  }
                ],
                "mentions": [],
                "tags": [],
                "emojis": [],
                "card": {
                  "url": "https://example.com/money/sort-code",
                  "title": "I lost my inheritance with one wrong digit on my sort code",
                  "description": "When the money went to another customer, the bank offered 25 as a token gesture",
                  "type": "link",
                  "author_name": "",
                  "author_url": "",
                  "provider_name": "",
                  "provider_url": "",
                  "html": "",
                  "width": 0,
                  "height": 0,
                  "image": null,
                  "embed_url": ""
                },
                "poll": null,
                "quote": {
                  "state": "accepted",
                  "quoted_status_id": "103270115826048975"
                },
                "some_future_field": { "nested": [1, 2, 3] }
              },
              {
                "id": "2222",
                "created_at": "2026-10-15T01:23:45.678Z",
                "edited_at": "2026-10-15T02:00:00.000Z",
                "in_reply_to_id": "1111",
                "in_reply_to_account_id": "103641",
                "sensitive": true,
                "spoiler_text": "cw",
                "visibility": "unlisted",
                "language": null,
                "uri": "https://m.skyblond.info/users/skyblond/statuses/2222",
                "content": "<p>edited toot</p>",
                "reblog": null,
                "account": { "id": "103641", "username": "skyblond", "acct": "skyblond" },
                "media_attachments": [],
                "mentions": [],
                "tags": [],
                "emojis": [],
                "card": null,
                "quote": {
                  "state": "pending",
                  "quoted_status": {
                    "id": "999",
                    "created_at": "2025-01-01T00:00:00.000Z",
                    "in_reply_to_id": null,
                    "in_reply_to_account_id": null,
                    "sensitive": false,
                    "spoiler_text": "",
                    "visibility": "public",
                    "language": "en",
                    "content": "<p>quoted</p>",
                    "media_attachments": []
                  }
                },
                "poll": {
                  "id": "34830",
                  "expires_at": "2019-12-05T04:05:08.302Z",
                  "expired": true,
                  "multiple": false,
                  "votes_count": 10,
                  "voters_count": null,
                  "options": [
                    { "title": "accept", "votes_count": 6 },
                    { "title": "deny", "votes_count": 4 }
                  ],
                  "emojis": []
                }
              }
            ]
            """.trimIndent()
        )

        val statuses = runBlocking { client.getStatuses("14715", limit = 40) }

        assertEquals(2, statuses.size)

        val first = statuses[0]
        assertEquals("108880211901672326", first.id)
        assertEquals("2022-08-24T22:29:46.493Z", first.createdAt)
        assertNull(first.editedAt)
        assertNull(first.inReplyToId)
        assertNull(first.inReplyToAccountId)
        assertEquals("en", first.language)
        assertEquals("public", first.visibility)
        assertEquals(false, first.sensitive)
        assertEquals(1, first.mediaAttachments.size)
        assertEquals("image", first.mediaAttachments[0].type)
        assertEquals("test media description", first.mediaAttachments[0].description)
        assertEquals("accepted", first.quote?.state)
        assertEquals("103270115826048975", first.quote?.quotedStatusId)
        assertEquals("link", first.card?.type)
        assertNull(first.poll)

        val second = statuses[1]
        assertEquals("2222", second.id)
        assertEquals("2026-10-15T02:00:00.000Z", second.editedAt)
        assertEquals("1111", second.inReplyToId)
        assertEquals("103641", second.inReplyToAccountId)
        assertNull(second.language)
        assertEquals("unlisted", second.visibility)
        assertEquals(true, second.sensitive)
        assertEquals(0, second.mediaAttachments.size)
        // full Quote wire shape: only the state is parsed,
        // the quoted_status object is skipped
        assertEquals("pending", second.quote?.state)
        assertNull(second.quote?.quotedStatusId)
        assertNull(second.card)
        val poll = second.poll!!
        assertEquals(2, poll.options.size)
        assertEquals("accept", poll.options[0].title)
        assertEquals("deny", poll.options[1].title)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertEquals("/api/v1/accounts/14715/statuses?limit=40", recorded.target)
        assertEquals("Bearer test-token", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
    }

    @Test
    fun `getStatuses returns an empty list when there are no statuses`() {
        enqueueJson(200, "[]")

        val statuses = runBlocking { client.getStatuses("14715", limit = 20) }

        assertEquals(0, statuses.size)
    }

    @Test
    fun `getStatuses sends sinceId and maxId for a bounded window`() {
        // everything strictly between two known toots, e.g.
        // catching up after downtime
        enqueueJson(200, "[]")

        runBlocking {
            client.getStatuses("14715", limit = 5, sinceId = "100", maxId = "200")
        }

        val recorded = server.takeRequest()
        assertEquals(
            "/api/v1/accounts/14715/statuses?limit=5&since_id=100&max_id=200",
            recorded.target
        )
    }

    @Test
    fun `getStatuses sends minId to paginate forward`() {
        // a forward cursor: walk newer toots page by page when
        // more than `limit` of them are new
        enqueueJson(200, "[]")

        runBlocking {
            client.getStatuses("14715", limit = 5, minId = "50")
        }

        val recorded = server.takeRequest()
        assertEquals(
            "/api/v1/accounts/14715/statuses?limit=5&min_id=50",
            recorded.target
        )
    }

    @Test
    fun `getStatuses only sends non-null cursors`() {
        enqueueJson(200, "[]")

        runBlocking { client.getStatuses("14715", limit = 20, maxId = "200") }

        val recorded = server.takeRequest()
        assertEquals("/api/v1/accounts/14715/statuses?limit=20&max_id=200", recorded.target)
    }

    @Test
    fun `getStatuses surfaces the server error on 404`() {
        enqueueJson(404, """{"error": "Record not found"}""")

        val e = runBlocking {
            assertFailsWith<MastoApiException> { client.getStatuses("404", limit = 20) }
        }

        assertEquals(404, e.httpCode)
        assertEquals("Record not found", e.detail)
    }

    @Test
    fun `getStatuses rejects a limit above the API maximum`() {
        val e = runBlocking {
            assertFailsWith<IllegalArgumentException> { client.getStatuses("14715", limit = 41) }
        }

        assertEquals("limit must be within 1..40, got 41", e.message)
    }

    @Test
    fun `getStatuses rejects a zero limit`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> { client.getStatuses("14715", limit = 0) }
        }
    }

    @Test
    fun `getStatuses rejects a blank account id`() {
        val e = runBlocking {
            assertFailsWith<IllegalArgumentException> { client.getStatuses(" ", limit = 20) }
        }

        assertEquals("accountId must not be blank", e.message)
    }

    @Test
    fun `getStatuses rejects blank pagination cursors`() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                client.getStatuses("14715", limit = 20, sinceId = " ")
            }
            assertFailsWith<IllegalArgumentException> {
                client.getStatuses("14715", limit = 20, minId = "")
            }
            assertFailsWith<IllegalArgumentException> {
                client.getStatuses("14715", limit = 20, maxId = "")
            }
        }
    }
}
