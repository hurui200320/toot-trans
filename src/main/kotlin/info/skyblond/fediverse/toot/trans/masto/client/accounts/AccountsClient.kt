package info.skyblond.fediverse.toot.trans.masto.client.accounts

import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status

interface AccountsClient {
    /**
     * GET /api/v1/accounts/verify_credentials HTTP/1.1
     * https://docs.joinmastodon.org/methods/accounts/#verify_credentials
     * */
    suspend fun verifyCredentials(): CredentialAccount

    /**
     * GET /api/v1/accounts/:accountId/statuses HTTP/1.1
     * https://docs.joinmastodon.org/methods/accounts/#statuses
     *
     * Returns each parsed status with the instant its page fetch was
     * issued, see [FetchedStatus].
     *
     * [excludeReblogs] maps to the `exclude_reblogs` query parameter;
     * it defaults to the server's default (false) to keep this
     * client general-purpose — callers decide the filtering policy.
     * */
    suspend fun getStatuses(
        accountId: String,
        limit: Int = GET_STATUSES_LIMIT,
        sinceId: String? = null,
        minId: String? = null,
        maxId: String? = null,
        excludeReblogs: Boolean = false,
    ): List<FetchedStatus>

    companion object {
        const val GET_STATUSES_LIMIT = 40
    }
}
