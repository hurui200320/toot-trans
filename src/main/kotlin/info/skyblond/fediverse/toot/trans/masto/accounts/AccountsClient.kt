package info.skyblond.fediverse.toot.trans.masto.accounts

import info.skyblond.fediverse.toot.trans.masto.statuses.Status

interface AccountsClient {
    /**
     * GET /api/v1/accounts/verify_credentials HTTP/1.1
     * https://docs.joinmastodon.org/methods/accounts/#verify_credentials
     * */
    suspend fun verifyCredentials(): CredentialAccount

    /**
     * GET /api/v1/accounts/:accountId/statuses HTTP/1.1
     * https://docs.joinmastodon.org/methods/accounts/#statuses
     * */
    suspend fun getStatuses(
        accountId: String,
        limit: Int,
        sinceId: String? = null,
        minId: String? = null,
        maxId: String? = null,
    ): List<Status>
}
