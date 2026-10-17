package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Qualify the entire ancestry, not just the immediate parent: every reply
 * must name [account] as its parent account, ending at a non-reply. A reply
 * with no parent-account metadata is inconclusive, not a self-reply.
 *
 * Missing ancestors and cycles fail closed. There is no permissive depth
 * limit. Reads use the pool; transport/API failures and cancellation propagate.
 */
suspend fun StatusesCachePool.checkReplyChain(
    status: Status,
    account: CredentialAccount,
): Boolean {
    val visited = hashSetOf(status.id)
    var current = status
    while (true) {
        currentCoroutineContext().ensureActive()
        // reached the root
        if (current.inReplyToId.isNullOrBlank()) {
            // also no reply account: good
            // otherwise is broken, we should skip it
            return current.inReplyToAccountId == null
        }
        // not root, must reply to us
        if (current.inReplyToAccountId != account.id) return false

        val parentId = current.inReplyToId
        // detect infinite loop
        if (!visited.add(parentId)) return false
        // find next parent loop
        current = getStatus(parentId) ?: return false
    }
}
