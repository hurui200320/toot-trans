package info.skyblond.fediverse.toot.trans.masto.client.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import java.io.IOException

/**
 * [MediaLoader] over a caller-injected [OkHttpClient], no auth: the
 * instance serves cached media URLs publicly, and the injected client
 * keeps connections and threads shared with the rest of the app.
 *
 * The wire contract (bounds, response checks) lives on [MediaLoader].
 * A `Content-Type` parameter list (e.g. `image/png; charset=binary`) is
 * stripped so the bare type reaches
 * [info.skyblond.fediverse.toot.trans.llm.client.ContentPart.image],
 * which rejects anything else.
 * */
class OkHttpMediaLoader(
    private val httpClient: OkHttpClient,
) : MediaLoader {

    override suspend fun load(url: String): LoadedMedia {
        require(url.isNotBlank()) { "url must not be blank" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        val response = httpClient.newCall(request).executeAsync()
        response.use {
            if (!it.isSuccessful) {
                throw IOException("HTTP ${it.code} downloading media $url")
            }
            val mediaType = it.header("Content-Type")
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
                ?.takeIf { type -> type.startsWith("image/") }
                ?: throw IOException(
                    "Media $url is not an image (Content-Type: ${it.header("Content-Type")})"
                )
            val declared = it.body.contentLength()
            if (declared > MediaLoader.MAX_IMAGE_BYTES) {
                throw IOException(
                    "Media $url declares $declared bytes, above the ${MediaLoader.MAX_IMAGE_BYTES} cap"
                )
            }
            val bytes = withContext(Dispatchers.IO) { readBounded(it.body, url) }
            return LoadedMedia(bytes, mediaType)
        }
    }

    /**
     * Read at most [MediaLoader.MAX_IMAGE_BYTES]: request one byte past
     * the cap, so a body that exceeds it is detected before it is
     * materialized. The blocking socket read runs on [Dispatchers.IO]
     * like the instance client's body reads.
     * */
    private fun readBounded(body: ResponseBody, url: String): ByteArray {
        val cap = MediaLoader.MAX_IMAGE_BYTES.toLong()
        val source = body.source()
        source.request(cap + 1)
        if (source.buffer.size > cap) {
            throw IOException("Media $url exceeds the ${MediaLoader.MAX_IMAGE_BYTES} byte cap")
        }
        return source.readByteArray()
    }

    companion object {
        /**
         * `User-Agent` sent with every download: mirrors the instance
         * API client's value, so hosts can identify the bot.
         * */
        private const val USER_AGENT = "toot-trans"
    }
}
