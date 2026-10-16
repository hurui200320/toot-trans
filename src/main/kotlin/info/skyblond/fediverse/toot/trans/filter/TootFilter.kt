package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent
import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import info.skyblond.fediverse.toot.trans.masto.statuses.checkReplyChain
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jsoup.Jsoup
import java.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Eligibility selection over the owner's scanned statuses: picks the
 * originals worth translating, oldest effective time first, using the
 * shared [pool] for ancestry lookups. Read-only.
 *
 * Eligibility only: whether an eligible original already has a translation
 * chain, and which segments to delete before retranslating it, is decided
 * from the same scan by `findTranslationChainState` in
 * `masto/statuses/TranslationChain.kt`.
 */
class TootFilter(
    private val pool: StatusesPool,
    private val targetLangCode: String,
    private val cooldown: Duration = DEFAULT_COOLDOWN,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(targetLangCode.isNotBlank()) { "targetLangCode must not be blank" }
        require(cooldown.isFinite() && cooldown >= Duration.ZERO) {
            "translationCooldown must be finite and non-negative"
        }
    }

    /**
     * Return the eligible originals from [statuses], oldest effective time
     * first. [statuses] is the owner's scan as `performInitialScan` in
     * `Scan.kt` fetched it (already seeded into the pool): a mix of
     * originals and translated toots, without boosts.
     *
     * Exclude footer posts in any language, target-language posts, and
     * blank body/CW. Null language is eligible. The cooldown uses
     * `editedAt ?: createdAt`; zero disables it, except for future
     * timestamps. Ancestry must pass [checkReplyChain] against [account].
     * API/transport failures and cancellation propagate.
     * */
    suspend fun filter(statuses: List<Status>, account: CredentialAccount): List<Status> {
        val now = clock.instant()
        val wait = cooldown.toJavaDuration()
        val pending = mutableListOf<Status>()
        for (status in statuses.sortedBy { it.editedAt ?: it.createdAt }) {
            currentCoroutineContext().ensureActive()
            // too new
            if (java.time.Duration.between(status.editedAt ?: status.createdAt, now) < wait) continue
            // already in target lang
            if (status.language == targetLangCode) continue
            // has footer
            if (status.content?.matchFooterFromContent() != null) continue
            // body and content warning is all blank
            val body = Jsoup.parseBodyFragment(status.content.orEmpty()).body().text()
            if (body.isBlank() && status.spoilerText.isNullOrBlank()) continue
            // reply chain is not valid
            if (!pool.checkReplyChain(status, account)) continue

            // accepted
            pending.add(status)
        }
        return pending
    }

    companion object {
        /** Single source of truth for the deploy and programmatic cooldown default. */
        val DEFAULT_COOLDOWN: Duration = 3.minutes
    }
}
