package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiClient
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * [StatusesClient] on top of [MastoApiClient].
 * The wire protocol is documented on [StatusesClient].
 * */
class OkHttpStatusesClient(
    private val api: MastoApiClient,
) : StatusesClient {

    private companion object {
        /**
         * Allowed `visibility` values, see the wire docs on
         * [StatusesClient.createStatus].
         * */
        val VISIBILITIES = setOf("public", "unlisted", "private", "direct")
    }

    override suspend fun getStatus(statusId: String): Status {
        require(statusId.isNotBlank()) { "statusId must not be blank" }
        return api.get(
            path = listOf("api", "v1", "statuses", statusId),
            deserializer = Status.serializer(),
        )
    }

    override suspend fun getStatuses(statusIds: Set<String>): List<Status> {
        require(statusIds.isNotEmpty()) { "statusIds must not be empty" }
        require(statusIds.size <= StatusesClient.MAX_IDS_PER_CALL) {
            "statusIds must hold at most ${StatusesClient.MAX_IDS_PER_CALL} ids, got ${statusIds.size}"
        }
        require(statusIds.none { it.isBlank() }) { "statusIds must not contain blank ids" }
        return api.get(
            path = listOf("api", "v1", "statuses"),
            // the id set maps to repeated id[] query parameters,
            // see the wire docs on [StatusesClient.getStatuses]
            params = mapOf("id[]" to statusIds.toList()),
            deserializer = ListSerializer(Status.serializer()),
        )
    }

    override suspend fun getStatusSource(statusId: String): StatusSource {
        require(statusId.isNotBlank()) { "statusId must not be blank" }
        return api.get(
            path = listOf("api", "v1", "statuses", statusId, "source"),
            deserializer = StatusSource.serializer(),
        )
    }

    override suspend fun createStatus(draft: CreateStatusRequest): Status {
        require(draft.status.isNotBlank()) { "status must not be blank" }
        require(draft.requestId.isNotBlank()) { "requestId must not be blank" }
        // https://docs.joinmastodon.org/methods/statuses/#create
        require(draft.visibility == null || draft.visibility in VISIBILITIES) {
            "visibility must be one of $VISIBILITIES, got `${draft.visibility}`"
        }
        require(draft.inReplyToId == null || draft.inReplyToId.isNotBlank()) {
            "inReplyToId must not be blank"
        }
        require(draft.language == null || draft.language.isNotBlank()) {
            "language must not be blank"
        }
        require(draft.spoilerText == null || draft.spoilerText.isNotBlank()) {
            "spoilerText must not be blank"
        }

        // requestId is local-only, see [CreateStatusRequest]
        val formParams = buildMap {
            put("status", draft.status)
            draft.inReplyToId?.let { put("in_reply_to_id", it) }
            draft.language?.let { put("language", it) }
            draft.visibility?.let { put("visibility", it) }
            draft.spoilerText?.let { put("spoiler_text", it) }
            draft.sensitive?.let { put("sensitive", it.toString()) }
        }

        return api.post(
            path = listOf("api", "v1", "statuses"),
            formParams = formParams,
            deserializer = Status.serializer(),
            headers = mapOf(
                "Idempotency-Key" to idempotencyKey(draft.requestId, formParams)
            ),
        )
    }

    override suspend fun deleteStatus(statusId: String) {
        require(statusId.isNotBlank()) { "statusId must not be blank" }
        api.delete(path = listOf("api", "v1", "statuses", statusId))
    }

    /**
     * Value of the `Idempotency-Key` header; the derivation formula
     * and its semantics are documented on [StatusesClient.createStatus].
     * */
    private fun idempotencyKey(requestId: String, formParams: Map<String, String>): String {
        val canonical = "$requestId|" +
            formParams.entries.joinToString("&") {
                // values are encoded, so a value containing "&" or "="
                // cannot impersonate another pair; see
                // [StatusesClient.createStatus] for the full derivation
                "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
            }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }
}
