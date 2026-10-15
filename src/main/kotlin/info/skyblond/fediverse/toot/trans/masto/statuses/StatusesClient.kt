package info.skyblond.fediverse.toot.trans.masto.statuses

interface StatusesClient {
    companion object {
        /**
         * Cap on ids per [StatusesClient.getStatuses] call, see its
         * wire docs.
         * */
        const val MAX_IDS_PER_CALL = 20
    }

    /**
     * GET /api/v1/statuses/:statusId HTTP/1.1
     * https://docs.joinmastodon.org/methods/statuses/#get
     * */
    suspend fun getStatus(statusId: String): Status

    /**
     * GET /api/v1/statuses HTTP/1.1
     * https://docs.joinmastodon.org/methods/statuses/#index
     *
     * The ids are sent as repeated `id[]` query parameters, e.g.
     * `?id[]=1&id[]=2`. At most [MAX_IDS_PER_CALL] ids per call:
     * the instance rejects more with 422. Added in Mastodon 4.3.0;
     * older instances answer 404. [statusIds] is a Set, mirroring
     * the instance deduplicating ids before enforcing the cap.
     *
     * Statuses the instance cannot find or deems invisible to the
     * current token are silently omitted, so the returned list can
     * be shorter than [statusIds], and its order is not guaranteed
     * to match [statusIds] either.
     * */
    suspend fun getStatuses(statusIds: Set<String>): List<Status>

    /**
     * GET /api/v1/statuses/:statusId/source HTTP/1.1
     * https://docs.joinmastodon.org/methods/statuses/#source
     * only available for statuses owned by the token account
     * */
    suspend fun getStatusSource(statusId: String): StatusSource

    /**
     * POST /api/v1/statuses HTTP/1.1
     * https://docs.joinmastodon.org/methods/statuses/#create
     *
     * The form body carries the non-null fields of CreateStatusRequest;
     * requestId is local-only, see the model.
     *
     * Additionally an `Idempotency-Key` header is sent: the SHA-256 hex
     * of "<requestId>|" followed by the "<name>=<value>" pairs joined
     * with "&" in the form body order, with each value URL-encoded.
     * The encoding keeps the derivation injective, so two different
     * requests (e.g. status text "x&language=en" vs status "x" plus
     * language "en") never collide into the same key, while the same
     * logical request (same requestId and same content) still maps to
     * the same key; the instance deduplicates submissions of a stored
     * key (keys are kept for about 1 hour).
     * */
    suspend fun createStatus(draft: CreateStatusRequest): Status

    /**
     * DELETE /api/v1/statuses/:statusId HTTP/1.1
     * https://docs.joinmastodon.org/methods/statuses/#delete
     * the response (the deleted Status with source text) is discarded
     * */
    suspend fun deleteStatus(statusId: String)
}