package info.skyblond.fediverse.toot.trans.llm.client

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Scaffolding shared by the chat client tests: a [MockWebServer] standing in for
 * the chat completions provider, with a client pointed at it.
 * */
abstract class ChatClientTestBase {
    protected lateinit var server: MockWebServer

    /**
     * The transport the client under test uses, built with the client's
     * recommended timeouts like the production wiring should.
     * */
    protected lateinit var httpClient: OkHttpClient

    protected lateinit var client: OkHttpChatClient

    /**
     * Parses recorded request bodies and builds expected wire snippets; the
     * client's own [Json] instance is private.
     * */
    protected val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUpClient() {
        server = MockWebServer()
        server.start()
        httpClient = OkHttpClient.Builder()
            .connectTimeout(OkHttpChatClient.RECOMMENDED_CONNECT_TIMEOUT)
            .readTimeout(OkHttpChatClient.RECOMMENDED_READ_TIMEOUT)
            .writeTimeout(OkHttpChatClient.RECOMMENDED_WRITE_TIMEOUT)
            .build()
        client = OkHttpChatClient(
            baseUrl = server.url("/").toString(),
            apiKey = "test-key",
            httpClient = httpClient,
        )
    }

    @AfterEach
    fun tearDownClient() {
        server.close()
    }

    /**
     * Enqueue [body] as a JSON response with status [code].
     * */
    protected fun enqueueJson(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .body(body)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .build()
        )
    }

    /**
     * Enqueue a minimal 200 Chat Completions response whose first choice carries
     * [content] as a plain string.
     * */
    protected fun enqueueCompletion(content: String = "Hello", finishReason: String = "stop") {
        enqueueJson(200, completionBody(content, finishReason))
    }

    protected fun completionBody(content: String = "Hello", finishReason: String = "stop"): String =
        """
        {
          "id": "chatcmpl-test",
          "object": "chat.completion",
          "created": 1750000000,
          "model": "test-model",
          "choices": [
            {
              "index": 0,
              "message": {"role": "assistant", "content": ${JsonPrimitive(content)}},
              "finish_reason": "$finishReason"
            }
          ],
          "usage": {"prompt_tokens": 3, "completion_tokens": 4, "total_tokens": 7}
        }
        """.trimIndent()

    /**
     * The last recorded request's body as UTF-8 text; `body` is nullable in
     * mockwebserver3, and the requests we assert on always have one.
     * */
    protected fun takeRequestBody(): String =
        requireNotNull(server.takeRequest().body).utf8()

    /**
     * The last recorded request's body as a parsed JSON object.
     * */
    protected fun takeRequestBodyJson(): JsonObject =
        json.parseToJsonElement(takeRequestBody()).jsonObject
}
