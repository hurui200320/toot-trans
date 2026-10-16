package info.skyblond.fediverse.toot.trans.llm.client

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.SocketEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.Base64

class OkHttpChatClientTest : ChatClientTestBase() {

    private fun request(
        messages: List<ChatMessage> = listOf(ChatMessage.user("hi")),
        maxCompletionTokens: Int? = null,
        reasoningEffort: ReasoningEffort? = null,
        extra: JsonObject? = null,
    ) = ChatCompletionRequest(
        model = "test-model",
        messages = messages,
        maxCompletionTokens = maxCompletionTokens,
        reasoningEffort = reasoningEffort,
        extra = extra,
    )

    @Test
    fun `posts to the endpoint with bearer auth and a JSON content type`() {
        enqueueCompletion()

        val response = runBlocking { client.complete(request()) }

        assertEquals("Hello", response.firstChoiceText())
        assertEquals("chatcmpl-test", response.id)
        assertEquals("test-model", response.model)
        assertEquals("stop", response.choices.single().finishReason)
        assertEquals(Usage(3, 4, 7), response.usage)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/chat/completions", recorded.target)
        assertEquals("Bearer test-key", recorded.headers["Authorization"])
        assertEquals("toot-trans", recorded.headers["User-Agent"])
        assertTrue(recorded.headers["Content-Type"].orEmpty().startsWith("application/json"))
    }

    @Test
    fun `a base URL path is kept before the endpoint`() {
        val pathClient = OkHttpChatClient(server.url("/v1/").toString(), "test-key", httpClient)
        enqueueCompletion()

        runBlocking { pathClient.complete(request()) }

        assertEquals("/v1/chat/completions", server.takeRequest().target)
    }

    @Test
    fun `a base URL without a trailing slash also works`() {
        val pathClient = OkHttpChatClient(server.url("/v1").toString(), "test-key", httpClient)
        enqueueCompletion()

        runBlocking { pathClient.complete(request()) }

        assertEquals("/v1/chat/completions", server.takeRequest().target)
    }

    @Test
    fun `text-only content is a JSON string and stream is pinned to false`() {
        enqueueCompletion()

        runBlocking {
            client.complete(
                request(
                    messages = listOf(
                        ChatMessage.system("translate this"),
                        ChatMessage.user("你好"),
                        ChatMessage.assistant("Hello"),
                    )
                )
            )
        }

        val body = takeRequestBodyJson()
        assertEquals("test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals(false, body.getValue("stream").jsonPrimitive.boolean)
        val messages = body.getValue("messages").jsonArray
        assertEquals(3, messages.size)
        assertEquals(
            listOf("system", "user", "assistant"),
            messages.map { it.jsonObject.getValue("role").jsonPrimitive.content },
        )
        assertEquals(JsonPrimitive("translate this"), messages[0].jsonObject.getValue("content"))
        // unset optional knobs stay off the wire
        assertFalse(body.containsKey("max_completion_tokens"))
        assertFalse(body.containsKey("reasoning_effort"))
        assertFalse(body.containsKey("max_tokens"))
        assertFalse(body.containsKey("temperature"))
        assertFalse(body.containsKey("extra"))
    }

    @Test
    fun `max completion tokens and reasoning effort use the standard wire names`() {
        enqueueCompletion()

        runBlocking {
            client.complete(request(maxCompletionTokens = 512, reasoningEffort = ReasoningEffort.NONE))
        }

        val body = takeRequestBodyJson()
        assertEquals(512, body.getValue("max_completion_tokens").jsonPrimitive.int)
        assertEquals("none", body.getValue("reasoning_effort").jsonPrimitive.content)
        assertFalse(body.containsKey("max_tokens"))
    }

    @Test
    fun `images are sent as base64 data URL parts`() {
        enqueueCompletion()
        val bytes = byteArrayOf(1, 2, 3)

        runBlocking {
            client.complete(
                request(
                    messages = listOf(
                        ChatMessage.user(
                            listOf(ContentPart.TextPart("what is this"), ContentPart.image(bytes, "image/png", ImageDetail.LOW))
                        )
                    )
                )
            )
        }

        val content = takeRequestBodyJson().getValue("messages").jsonArray[0]
            .jsonObject.getValue("content").jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("what is this", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        val imagePart = content[1].jsonObject
        assertEquals("image_url", imagePart.getValue("type").jsonPrimitive.content)
        val imageUrl = imagePart.getValue("image_url").jsonObject
        assertEquals(
            "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
            imageUrl.getValue("url").jsonPrimitive.content,
        )
        assertEquals("low", imageUrl.getValue("detail").jsonPrimitive.content)
    }

    @Test
    fun `extra fields are merged into the top level of the body`() {
        enqueueCompletion()
        val extra = buildJsonObject {
            put("temperature", 0.2)
            putJsonObject("chat_template_kwargs") { put("enable_thinking", false) }
        }

        runBlocking { client.complete(request(extra = extra)) }

        val body = takeRequestBodyJson()
        assertEquals(0.2, body.getValue("temperature").jsonPrimitive.double)
        assertEquals(
            false,
            body.getValue("chat_template_kwargs").jsonObject.getValue("enable_thinking").jsonPrimitive.boolean,
        )
        assertFalse(body.containsKey("extra"))
    }

    @Test
    fun `an extra object mutated after construction cannot inject client-owned fields`() {
        // JsonObject delegates to the caller's backing map, so the mutation below is
        // visible to the client even though the constructor validation passed
        val backing = mutableMapOf<String, JsonElement>("temperature" to JsonPrimitive(0.2))
        val request = request(extra = JsonObject(backing))
        backing["model"] = JsonPrimitive("hijacked")

        val e = runBlocking { assertFailsWith<IllegalArgumentException> { client.complete(request) } }

        assertEquals(
            "extra must not override client-owned fields ${ChatCompletionRequest.RESERVED_KEYS}, " +
                "got [temperature, model]",
            e.message,
        )
    }

    @Test
    fun `array response content joins its text parts and drops unmodeled parts`() {
        enqueueJson(
            200,
            """
            {
              "id": "chatcmpl-parts",
              "choices": [
                {
                  "index": 0,
                  "finish_reason": "stop",
                  "message": {
                    "role": "assistant",
                    "content": [
                      {"type": "text", "text": "Hello"},
                      {"type": "refusal", "refusal": "nice try"},
                      {"type": "text", "text": "world"}
                    ]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val response = runBlocking { client.complete(request()) }

        assertEquals("Hello\nworld", response.firstChoiceText())
    }

    @Test
    fun `a parts response without a text part fails fast on text extraction`() {
        enqueueJson(
            200,
            """
            {
              "id": "chatcmpl-refusal",
              "choices": [
                {
                  "index": 0,
                  "finish_reason": "content_filter",
                  "message": {
                    "role": "assistant",
                    "content": [{"type": "refusal", "refusal": "nice try"}]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val response = runBlocking { client.complete(request()) }

        val e = assertFailsWith<ChatCompletionException> { response.firstChoiceText() }
        assertEquals("First choice content has no text parts (finishReason=content_filter)", e.message)
    }

    @Test
    fun `a response without choices fails fast on text extraction`() {
        enqueueJson(200, """{"id": "chatcmpl-empty", "choices": []}""")

        val response = runBlocking { client.complete(request()) }

        val e = assertFailsWith<ChatCompletionException> { response.firstChoiceText() }
        assertEquals("Response has no choices (id=chatcmpl-empty)", e.message)
    }

    @Test
    fun `a choice without content fails fast on text extraction`() {
        enqueueJson(
            200,
            """{"id": "x", "choices": [""" +
                """{"index": 0, "finish_reason": "length", "message": {"role": "assistant", "content": null}}]}""",
        )

        val response = runBlocking { client.complete(request()) }

        val e = assertFailsWith<ChatCompletionException> { response.firstChoiceText() }
        assertEquals("First choice has no content (finishReason=length)", e.message)
    }

    @Test
    fun `blank answer content fails fast on text extraction`() {
        // a thinking model can drain its whole budget into its unmodeled reasoning
        // field and answer with an empty string, which must not pass as a translation
        enqueueJson(
            200,
            """{"id": "x", "choices": [""" +
                """{"index": 0, "finish_reason": "length", "message": {"role": "assistant", "content": ""}}]}""",
        )

        val response = runBlocking { client.complete(request()) }

        val e = assertFailsWith<ChatCompletionException> { response.firstChoiceText() }
        assertEquals("First choice text is blank (finishReason=length)", e.message)
    }

    @Test
    fun `whitespace-only text parts fail fast on text extraction`() {
        enqueueJson(
            200,
            """
            {
              "id": "x",
              "choices": [
                {
                  "index": 0,
                  "finish_reason": "stop",
                  "message": {
                    "role": "assistant",
                    "content": [{"type": "text", "text": " "}]
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val response = runBlocking { client.complete(request()) }

        val e = assertFailsWith<ChatCompletionException> { response.firstChoiceText() }
        assertEquals("First choice text is blank (finishReason=stop)", e.message)
    }

    @Test
    fun `malformed success bodies fail fast`() {
        enqueueJson(200, """{"id": "x",""")

        runBlocking {
            assertFailsWith<SerializationException> { client.complete(request()) }
        }
    }

    @Test
    fun `a choice without a message object fails fast on decoding`() {
        enqueueJson(200, """{"id": "x", "choices": [{"index": 0, "finish_reason": "stop"}]}""")

        runBlocking {
            assertFailsWith<SerializationException> { client.complete(request()) }
        }
    }

    @Test
    fun `nested error bodies map to ChatApiException with provider details`() {
        enqueueJson(
            429,
            """{"error": {"message": "Rate limit reached", "type": "rate_limit_error", """ +
                """"param": null, "code": "rate_limit_exceeded"}}""",
        )

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        assertEquals(429, e.httpCode)
        assertEquals("Rate limit reached", e.detail)
        assertEquals("rate_limit_error", e.errorType)
        assertEquals("rate_limit_exceeded", e.errorCode)
        assertEquals("HTTP 429: Rate limit reached", e.message)
    }

    @Test
    fun `bare string error bodies map to the detail`() {
        enqueueJson(502, """{"error": "upstream boom"}""")

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        assertEquals(502, e.httpCode)
        assertEquals("upstream boom", e.detail)
    }

    @Test
    fun `top-level error fields from inference servers are picked up`() {
        // vLLM-style: {"message": ..., "type": ..., "code": ...} without nesting
        enqueueJson(
            400,
            """{"object": "error", "message": "model not found", "type": "NotFoundError", "code": 404}""",
        )

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        assertEquals(400, e.httpCode)
        assertEquals("model not found", e.detail)
        assertEquals("NotFoundError", e.errorType)
        assertEquals("404", e.errorCode)
    }

    @Test
    fun `non-JSON error bodies fall back to a snippet`() {
        server.enqueue(MockResponse.Builder().code(502).body("<html>Bad Gateway</html>").build())

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

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

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        // the capped read cannot be valid JSON, so the snippet fallback kicks
        // in, truncated to 200 chars like any other snippet
        assertEquals(502, e.httpCode)
        assertEquals(
            "<unexpected error body: <html>" + "x".repeat(194) + ">",
            e.detail,
        )
    }

    @Test
    fun `an unreadable error body keeps the status code classification`() {
        // the server closes the socket halfway through the body, so reading the
        // error body fails while the status code is already known
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .setHeader("Content-Type", "application/json")
                .body("""{"error": {"message": "boom"}}""")
                .onResponseBody(SocketEffect.CloseSocket())
                .build()
        )

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        assertEquals(500, e.httpCode)
        assertEquals("<unreadable error body>", e.detail)
    }

    @Test
    fun `empty error bodies still fail with the status code`() {
        server.enqueue(MockResponse.Builder().code(500).build())

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        assertEquals(500, e.httpCode)
        assertEquals("<no error body>", e.detail)
    }

    @Test
    fun `error bodies without a message surface a snippet`() {
        enqueueJson(502, "{}")

        val e = runBlocking { assertFailsWith<ChatApiException> { client.complete(request()) } }

        assertEquals(502, e.httpCode)
        assertEquals("<error body without message: {}>", e.detail)
    }

    @Test
    fun `rejects a base URL that is not a URL`() {
        val e = assertFailsWith<IllegalArgumentException> {
            OkHttpChatClient("api.example.com/v1", "test-key", httpClient)
        }

        assertEquals("Base URL `api.example.com/v1` is not a valid URL", e.message)
    }

    @Test
    fun `rejects a base URL with a query or fragment`() {
        val query = assertFailsWith<IllegalArgumentException> {
            OkHttpChatClient("https://api.example.com/v1?key=x", "test-key", httpClient)
        }
        assertEquals(
            "Base URL `https://api.example.com/v1?key=x` must not contain a query or fragment",
            query.message,
        )

        val fragment = assertFailsWith<IllegalArgumentException> {
            OkHttpChatClient("https://api.example.com/v1#models", "test-key", httpClient)
        }
        assertEquals(
            "Base URL `https://api.example.com/v1#models` must not contain a query or fragment",
            fragment.message,
        )
    }

    @Test
    fun `rejects a base URL that already points at the endpoint`() {
        val e = assertFailsWith<IllegalArgumentException> {
            OkHttpChatClient("https://api.example.com/v1/chat/completions", "test-key", httpClient)
        }

        assertEquals(
            "Base URL `https://api.example.com/v1/chat/completions` must be the API root " +
                "(e.g. https://api.openai.com/v1), not the endpoint itself",
            e.message,
        )

        // the check is case-insensitive, so a differently cased endpoint is caught too
        val mixedCase = assertFailsWith<IllegalArgumentException> {
            OkHttpChatClient("https://api.example.com/v1/Chat/Completions", "test-key", httpClient)
        }
        assertEquals(
            "Base URL `https://api.example.com/v1/Chat/Completions` must be the API root " +
                "(e.g. https://api.openai.com/v1), not the endpoint itself",
            mixedCase.message,
        )
    }

    @Test
    fun `rejects a blank API key`() {
        val e = assertFailsWith<IllegalArgumentException> {
            OkHttpChatClient("https://api.example.com/v1", " ", httpClient)
        }

        assertEquals("apiKey must not be blank", e.message)
    }
}
