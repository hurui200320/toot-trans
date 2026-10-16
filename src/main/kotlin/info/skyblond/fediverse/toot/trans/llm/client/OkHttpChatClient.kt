package info.skyblond.fediverse.toot.trans.llm.client

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.blackholeSink
import java.io.IOException
import java.time.Duration

/**
 * [ChatClient] over OkHttp: one HTTP call per [complete], no retry (that belongs to
 * [RetryingChatClient]) and no streaming (see [ChatClient]).
 *
 * [baseUrl] is the API root and may contain a path, e.g.
 * `https://api.openai.com/v1` or a local llama.cpp server's `http://host:8080/v1`;
 * the transport appends `/chat/completions`. A query, fragment, or a base URL that
 * already names the endpoint fails fast.
 *
 * [httpClient] is injected by the caller: this transport neither builds nor owns
 * clients, so connection pooling and lifecycle stay with the wiring. Build the
 * injected client with the [RECOMMENDED_CONNECT_TIMEOUT], [RECOMMENDED_READ_TIMEOUT]
 * and [RECOMMENDED_WRITE_TIMEOUT] values: generation is slow (a chat completion
 * can take minutes on thinking models) and a request may upload base64 images,
 * so OkHttp's default read and write timeouts would cut long generations off,
 * while the short connect timeout keeps an unreachable endpoint failing fast.
 * Requests decode with unknown keys ignored; null optional fields are omitted
 * from the body.
 * */
class OkHttpChatClient(
    baseUrl: String,
    private val apiKey: String,
    private val httpClient: OkHttpClient,
) : ChatClient {

    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
    }

    private val json = Json {
        // providers return way more fields than the models care about
        ignoreUnknownKeys = true
        // optional fields left null are omitted instead of sent as JSON null
        explicitNulls = false
    }

    private val endpointUrl: HttpUrl = parseEndpointUrl(baseUrl)

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        val httpRequest = Request.Builder()
            .url(endpointUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", USER_AGENT)
            .post(buildRequestBody(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = httpClient.newCall(httpRequest).executeAsync()
        response.use {
            if (!it.isSuccessful) throw apiException(it)
            val decoded = json.decodeFromString(ChatCompletionResponse.serializer(), readBody(it))
            logger.debug {
                "Chat completion ${decoded.id} finished: model=${decoded.model}, usage=${decoded.usage}"
            }
            return decoded
        }
    }

    /**
     * Serialize [request] to the request body. [ChatCompletionRequest.extra] is
     * snapshotted and re-validated, then merged at the top level after the standard
     * fields (the caller's object could have changed after the `init` block ran, see
     * [ChatCompletionRequest.extra]), and `stream` is pinned to false here: this
     * client only implements non-streaming Chat Completions, see [ChatClient].
     * */
    private fun buildRequestBody(request: ChatCompletionRequest): String {
        val body = json.encodeToJsonElement(ChatCompletionRequest.serializer(), request)
            .jsonObject
            .toMutableMap()
        val extra = request.extra?.let { JsonObject(it.toMap()) }
        ChatCompletionRequest.requireExtraKeysAllowed(extra)
        extra?.forEach { (key, value) -> body[key] = value }
        body["stream"] = JsonPrimitive(false)
        return json.encodeToString(JsonObject.serializer(), JsonObject(body))
    }

    /**
     * Read the whole response body into a String. The underlying socket read
     * blocks the thread, so this runs on [Dispatchers.IO] instead of the caller's
     * dispatcher, like the instance API client does.
     * */
    private suspend fun readBody(response: Response): String =
        withContext(Dispatchers.IO) { response.body.string() }

    /**
     * Map a non-2xx [response] to [ChatApiException], extracting the provider's
     * error message/type/code when the body follows the common shapes
     * (`{"error": {message, type, code}}`, `{"error": "..."}`, or top-level
     * `message`/`type`/`code` as some inference servers answer), falling back to a
     * truncated raw snippet otherwise and to a status-only classification when the
     * body cannot be read at all. Mirrors the instance client's error
     * handling: see [info.skyblond.fediverse.toot.trans.masto.MastoApiClient].
     * */
    private suspend fun apiException(response: Response): ChatApiException {
        val raw = try {
            readErrorBody(response)
        } catch (_: IOException) {
            // the status code is already known: an unreadable body must not surface
            // a transport error instead, which callers would classify as retryable
            return ChatApiException(response.code, "<unreadable error body>")
        }
        if (raw.isBlank()) return ChatApiException(response.code, "<no error body>")

        val envelope = try {
            json.decodeFromString(ErrorEnvelope.serializer(), raw)
        } catch (_: SerializationException) {
            return ChatApiException(response.code, "<unexpected error body: ${raw.take(ERROR_SNIPPET_LENGTH)}>")
        }

        return when (val error = envelope.error?.takeUnless { it is JsonNull }) {
            is JsonObject -> {
                val detail = json.decodeFromJsonElement(ErrorObject.serializer(), error)
                ChatApiException(
                    httpCode = response.code,
                    detail = detail.message
                        ?: "<error body without message: ${raw.take(ERROR_SNIPPET_LENGTH)}>",
                    errorType = detail.type,
                    errorCode = detail.code.asString(),
                )
            }

            is JsonPrimitive -> ChatApiException(
                response.code,
                error.contentOrNull ?: "<error body without message: ${raw.take(ERROR_SNIPPET_LENGTH)}>",
            )

            null -> ChatApiException(
                httpCode = response.code,
                detail = envelope.message
                    ?: "<error body without message: ${raw.take(ERROR_SNIPPET_LENGTH)}>",
                errorType = envelope.type,
                errorCode = envelope.code.asString(),
            )

            else -> ChatApiException(response.code, "<unexpected error body: ${raw.take(ERROR_SNIPPET_LENGTH)}>")
        }
    }

    /**
     * Read at most [MAX_ERROR_BODY_BYTES] of the error body and drain the rest
     * without materializing it; identical semantics and rationale as
     * [info.skyblond.fediverse.toot.trans.masto.MastoApiClient]'s `readErrorBody`
     * (see its KDoc). The snippet bound here is [ERROR_SNIPPET_LENGTH].
     * */
    private suspend fun readErrorBody(response: Response): String =
        withContext(Dispatchers.IO) {
            val source = response.body.source()
            source.request(MAX_ERROR_BODY_BYTES)
            val raw = source.readUtf8(minOf(MAX_ERROR_BODY_BYTES, source.buffer.size))
            source.readAll(blackholeSink())
            raw
        }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Upper bound on error-body bytes read into memory, see [readErrorBody].
         * */
        private const val MAX_ERROR_BODY_BYTES = 2048L

        /**
         * Length of the raw-body snippet kept in error messages; every snippet
         * fallback in [apiException] shares it, so a pathological body cannot
         * bloat exception messages.
         * */
        private const val ERROR_SNIPPET_LENGTH = 200

        /**
         * `User-Agent` sent with every request: OpenAI-compatible gateways
         * commonly expect a descriptive client name.
         * */
        private const val USER_AGENT = "toot-trans"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Recommended connect timeout for the [OkHttpClient] the caller injects:
         * short, because an unreachable endpoint must fail fast.
         * */
        val RECOMMENDED_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)

        /**
         * Recommended read timeout for the [OkHttpClient] the caller injects:
         * generation is slow (a chat completion can take minutes on thinking
         * models), far beyond OkHttp's default.
         * */
        val RECOMMENDED_READ_TIMEOUT: Duration = Duration.ofMinutes(10)

        /**
         * Recommended write timeout for the [OkHttpClient] the caller injects: a
         * request may upload base64 images, which is slow on a constrained uplink.
         * */
        val RECOMMENDED_WRITE_TIMEOUT: Duration = Duration.ofMinutes(2)
    }
}

/**
 * Value of the `code` field of a provider error, whichever JSON shape it uses:
 * strings verbatim, other shapes serialized as-is.
 * */
private fun JsonElement?.asString(): String? = when (this) {
    is JsonPrimitive -> contentOrNull
    else -> this?.toString()
}

/**
 * Parse [baseUrl] as the API root and derive the `/chat/completions` endpoint.
 *
 * Unlike the instance API, a path is expected (`https://api.example.com/v1`), so
 * only a query or fragment is rejected. Passing the endpoint itself would silently
 * produce `/chat/completions/chat/completions`, so that is rejected too.
 * */
private fun parseEndpointUrl(baseUrl: String): HttpUrl {
    val url = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "Base URL `$baseUrl` is not a valid URL"
    }
    require(url.querySize == 0 && url.fragment == null) {
        "Base URL `$baseUrl` must not contain a query or fragment"
    }
    val trimmed = url.toString().trimEnd('/')
    require(!trimmed.endsWith("/chat/completions", ignoreCase = true)) {
        "Base URL `$baseUrl` must be the API root (e.g. https://api.openai.com/v1), not the endpoint itself"
    }
    return "$trimmed/chat/completions".toHttpUrl()
}

/**
 * Wire shape of the error body, see [OkHttpChatClient.apiException]: OpenAI nests
 * the error details under `error`, some servers answer with the details at the top
 * level, and some use a bare string.
 * */
@Serializable
private data class ErrorEnvelope(
    val error: JsonElement? = null,
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)

/**
 * The nested provider error object, see [ErrorEnvelope]. `code` is not always a
 * string (numbers occur), hence [JsonElement].
 * */
@Serializable
private data class ErrorObject(
    val message: String? = null,
    val type: String? = null,
    val code: JsonElement? = null,
)

/**
 * The provider responded with a non-2xx status.
 *
 * [httpCode] is the HTTP status code; [detail] is the provider's error message if
 * one could be extracted, otherwise a raw body snippet; [errorType] and
 * [errorCode] are the provider's own classification fields (e.g. `insufficient_quota`)
 * when present, for logging and for callers that need to distinguish quota
 * exhaustion from a plain rate limit.
 *
 * Retryability is decided by [RetryingChatClient], not here.
 * */
class ChatApiException(
    val httpCode: Int,
    val detail: String,
    val errorType: String? = null,
    val errorCode: String? = null,
) : RuntimeException("HTTP $httpCode: $detail")
