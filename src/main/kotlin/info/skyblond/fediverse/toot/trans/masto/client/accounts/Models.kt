package info.skyblond.fediverse.toot.trans.masto.client.accounts

import kotlinx.serialization.Serializable

/**
 * The account behind the access token.
 * Wire docs: see [AccountsClient.verifyCredentials].
 * */
@Serializable
data class CredentialAccount(
    val id: String,
    val username: String,
)
