package info.skyblond.fediverse.toot.trans.masto.client.accounts

import info.skyblond.fediverse.toot.trans.masto.MastoApiClient
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import kotlinx.serialization.builtins.ListSerializer
import java.time.Clock

/**
 * [AccountsClient] on top of [MastoApiClient].
 * The wire protocol is documented on [AccountsClient].
 *
 * [clock] stamps [FetchedStatus.fetchedAt] on each page; tests inject a
 * fixed clock for deterministic cooldown assertions.
 * */
class OkHttpAccountsClient(
    private val api: MastoApiClient,
    private val clock: Clock = Clock.systemUTC()
) : AccountsClient {

    override suspend fun verifyCredentials(): CredentialAccount =
        api.get(
            path = listOf("api", "v1", "accounts", "verify_credentials"),
            deserializer = CredentialAccount.serializer(),
        )

    override suspend fun getStatuses(
        accountId: String,
        limit: Int,
        sinceId: String?,
        minId: String?,
        maxId: String?,
        excludeReblogs: Boolean,
    ): List<FetchedStatus> {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        // https://docs.joinmastodon.org/methods/accounts/#statuses
        require(limit in 1..40) { "limit must be within 1..40, got $limit" }
        require(sinceId == null || sinceId.isNotBlank()) { "sinceId must not be blank" }
        require(minId == null || minId.isNotBlank()) { "minId must not be blank" }
        require(maxId == null || maxId.isNotBlank()) { "maxId must not be blank" }

        val params = buildMap {
            put("limit", listOf(limit.toString()))
            sinceId?.let { put("since_id", listOf(it)) }
            minId?.let { put("min_id", listOf(it)) }
            maxId?.let { put("max_id", listOf(it)) }
            // only non-default values go on the wire, like the cursors above
            if (excludeReblogs) put("exclude_reblogs", listOf("true"))
        }
        // one stamp per page, so every entry carries the same fetch time;
        // it is taken before the request goes out, see [FetchedStatus]
        val fetchedAt = clock.instant()
        return api.get(
            path = listOf("api", "v1", "accounts", accountId, "statuses"),
            params = params,
            deserializer = ListSerializer(Status.serializer()),
        ).map { FetchedStatus(it, fetchedAt) }
    }
}
