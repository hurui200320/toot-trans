package info.skyblond.fediverse.toot.trans.masto.client.media

/**
 * One downloaded image for the LLM vision input: the raw bytes and the
 * media type for [info.skyblond.fediverse.toot.trans.llm.client.ContentPart.image].
 * */
data class LoadedMedia(
    val bytes: ByteArray,
    val mediaType: String,
)

/**
 * Fetches a status media attachment for the translation stage.
 *
 * Only the toot under translation contributes images (see
 * `ReplyChainContext`), and only its `image` attachments: video, audio
 * and unknown types are skipped before this interface is called.
 * Callers pass `media_attachments[].url`, the instance's own cached
 * copy — never `remote_url`, which would point at a foreign host.
 *
 * Implementations must fail fast per image (transport errors,
 * non-image responses, oversized payloads); the caller treats one
 * failure as one skipped image, not a failed translation. The
 * [MAX_IMAGES_PER_REQUEST] / [MAX_IMAGE_BYTES] bounds exist because
 * base64 inflates payloads by about a third and every LLM retry
 * resends the whole request body (see
 * [info.skyblond.fediverse.toot.trans.llm.client.ContentPart.image]).
 * */
interface MediaLoader {
    /**
     * Download [url], which must be a non-blank absolute URL. Fails
     * fast with [java.io.IOException] when the response is not a
     * successful image within [MAX_IMAGE_BYTES].
     * */
    suspend fun load(url: String): LoadedMedia

    companion object {
        /**
         * Upper bound of images attached to one completion request.
         * Mastodon's default attachment cap per toot is 4, so one toot
         * cannot exceed it; the bound also keeps the request body small.
         * */
        const val MAX_IMAGES_PER_REQUEST = 4

        /**
         * Upper bound of one image's bytes, checked against both the
         * declared and the streamed size.
         * */
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    }
}
