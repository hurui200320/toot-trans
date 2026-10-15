package info.skyblond.fediverse.toot.trans.masto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import okio.blackholeSink

/**
 * Shared transport for the instance's REST API: authentication,
 * request execution and JSON decoding.
 *
 * Endpoint-specific clients (see `accounts/AccountsClient.kt` and
 * `statuses/StatusesClient.kt`) build on this class; they own the
 * wire protocol documentation, this class only deals with transport.
 * */
class MastoApiClient(
    /**
     * Base URL of the instance, for example `https://mastodon.social/`.
     * Only the scheme, host and port are used.
     * */
    baseUrl: String,
    private val accessToken: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private companion object {
        /**
         * Upper bound on error-body bytes read into memory, see
         * [readErrorBody].
         * */
        const val MAX_ERROR_BODY_BYTES = 2048L

        /**
         * `User-Agent` sent with every request: instances expect a
         * descriptive client name, and generic `okhttp/...` values
         * may be filtered out by their bot protection.
         * */
        const val USER_AGENT = "toot-trans"
    }

    private val json = Json {
        // instances return way more fields than the models care about
        ignoreUnknownKeys = true
    }

    private val baseHttpUrl: HttpUrl = parseBaseUrl(baseUrl)

    /**
     * GET [path] with [params] as the query string, and decode the
     * response body as [T]. Each name maps to the values sent under
     * it, one query parameter per entry: Mastodon array parameters
     * repeat one key (e.g. `id[]`), which a name-to-value map cannot
     * express.
     *
     * Fails fast: non-2xx responses throw [MastoApiException], malformed
     * JSON throws [SerializationException].
     * */
    suspend fun <T> get(
        path: List<String>,
        params: Map<String, List<String>> = emptyMap(),
        deserializer: DeserializationStrategy<T>,
    ): T {
        val url = requestUrl(path).apply {
            params.forEach { (name, values) ->
                values.forEach { value -> addQueryParameter(name, value) }
            }
        }.build()

        val request = authorizedRequest(url).get().build()
        return executeAndDecode(request) { json.decodeFromString(deserializer, it) }
    }

    /**
     * POST [path] with [formParams] as an
     * `application/x-www-form-urlencoded` body and [headers] as
     * additional request headers, then decode the response body as [T].
     * The `Authorization` header is owned by the access token and
     * rejected in [headers].
     *
     * Fails fast like [get].
     * */
    suspend fun <T> post(
        path: List<String>,
        formParams: Map<String, String> = emptyMap(),
        deserializer: DeserializationStrategy<T>,
        headers: Map<String, String> = emptyMap(),
    ): T {
        // a stray Authorization entry would silently replace the
        // credential; HTTP header names are case-insensitive
        require(headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            "Authorization is owned by the access token and must not be passed in headers"
        }

        val body = FormBody.Builder().apply {
            formParams.forEach { (name, value) -> add(name, value) }
        }.build()

        val request = authorizedRequest(requestUrl(path).build())
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(body)
            .build()

        return executeAndDecode(request) { json.decodeFromString(deserializer, it) }
    }

    /**
     * DELETE [path]. The response body is read (to release the
     * connection) but discarded.
     *
     * Fails fast: non-2xx responses throw [MastoApiException].
     * */
    suspend fun delete(path: List<String>) {
        val request = authorizedRequest(requestUrl(path).build()).delete().build()
        executeAndDecode(request) { }
    }

    /**
     * URL builder for [path] under the instance root. Each path
     * segment is URL-encoded on its own.
     * */
    private fun requestUrl(path: List<String>): HttpUrl.Builder =
        baseHttpUrl.newBuilder().apply {
            path.forEach { addPathSegment(it) }
        }

    private fun authorizedRequest(url: HttpUrl): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", USER_AGENT)

    private suspend fun <T> executeAndDecode(
        request: Request,
        decode: (String) -> T,
    ): T {
        // executeAsync suspends until the response headers arrive
        // and cancels the in-flight call when the calling coroutine
        // is cancelled; the body reads below still block a thread,
        // so they dispatch to Dispatchers.IO (see [readBody] and
        // [readErrorBody])
        val response = httpClient.newCall(request).executeAsync()
        response.use {
            if (!it.isSuccessful) {
                throw MastoApiException(it.code, errorDetail(it), rateLimitReset(it))
            }
            return decode(readBody(it))
        }
    }

    /**
     * Read the whole response body into a String. The underlying
     * socket read blocks the thread, so this runs on
     * [Dispatchers.IO] instead of the caller's dispatcher.
     * */
    private suspend fun readBody(response: Response): String =
        withContext(Dispatchers.IO) { response.body.string() }

    /**
     * Extract the server's error message. Non-2xx bodies are usually
     * the Error entity (`{"error": "..."}`), but proxies and bugs can
     * return anything, so fall back to a truncated raw body snippet.
     * https://docs.joinmastodon.org/entities/Error
     * */
    private suspend fun errorDetail(response: Response): String {
        val raw = readErrorBody(response)
        if (raw.isBlank()) return "<no error body>"
        return try {
            json.decodeFromString(MastoError.serializer(), raw).error
                ?: "<error body without message: ${raw.take(200)}>"
        } catch (e: SerializationException) {
            "<unexpected error body: ${raw.take(200)}>"
        }
    }

    /**
     * Read at most [MAX_ERROR_BODY_BYTES] of the error body: only a
     * 200-char snippet ever reaches the exception message (see
     * [errorDetail]), so a larger body is pathological and must not
     * be buffered in full. A body cut in half simply fails the JSON
     * parse and surfaces as a snippet. The remainder is drained
     * without materializing it, to keep the connection reusable.
     * Like [readBody], the blocking reads run on [Dispatchers.IO].
     * */
    private suspend fun readErrorBody(response: Response): String =
        withContext(Dispatchers.IO) {
            val source = response.body.source()
            source.request(MAX_ERROR_BODY_BYTES)
            val raw = source.readUtf8(minOf(MAX_ERROR_BODY_BYTES, source.buffer.size))
            source.readAll(blackholeSink())
            raw
        }

    /**
     * Value of the `X-RateLimit-Reset` header when the instance is rate
     * limiting; semantics: see [MastoApiException.rateLimitReset].
     * */
    private fun rateLimitReset(response: Response): String? =
        if (response.code == 429) response.header("X-RateLimit-Reset") else null
}

/**
 * Wire format of the error body, see [MastoApiClient.errorDetail].
 * */
@Serializable
private data class MastoError(
    val error: String? = null,
)

/**
 * The instance responded with a non-2xx status.
 *
 * [httpCode] is the HTTP status code; [detail] is the server's error
 * message if the body is the Error entity, otherwise a raw body snippet.
 * [rateLimitReset] is the instance's `X-RateLimit-Reset` header (an
 * ISO 8601 datetime string, e.g. `2026-10-15T00:05:00.000000Z`) when
 * rate limiting ([httpCode] == 429), otherwise null; callers backing
 * off should wait until then instead of guessing.
 * https://docs.joinmastodon.org/api/rate-limits/
 * */
class MastoApiException(
    val httpCode: Int,
    val detail: String,
    val rateLimitReset: String? = null,
) : RuntimeException("HTTP $httpCode: $detail")

/**
 * Parse [baseUrl] as an instance host. We always talk to the instance
 * root, so a path, query or fragment in the config is a mistake we
 * want to fail fast on instead of silently ignoring it. (HttpUrl only
 * accepts http/https, so the scheme is already covered.)
 * */
private fun parseBaseUrl(baseUrl: String): HttpUrl {
    val url = requireNotNull(baseUrl.toHttpUrlOrNull()) {
        "Base URL `$baseUrl` is not a valid URL"
    }
    require(url.encodedPath == "/" && url.querySize == 0 && url.fragment == null) {
        "Base URL `$baseUrl` must not contain a path, query or fragment"
    }
    return url
}
