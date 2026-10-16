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
 * Return pending originals, oldest effective time first, from the owner's scan
 * already seeded into [pool] (see `performInitialScan` in `Scan.kt`). Exclude
 * footer posts in any language, target-language posts, and blank body/CW.
 * Boosts are not re-checked here: the scan already excludes them server-side.
 * Null language is eligible. [cooldown] uses `editedAt ?: createdAt`; zero
 * disables it, except for future timestamps. Ancestry must pass [checkReplyChain].
 *
 * Any direct reply in this scan with a valid footer and [targetLangCode] means
 * translation has already started: skip the original without checking hashes,
 * segment order, or completeness. No cleanup or refresh is performed. The
 * accepted limitations and manual recovery procedure are documented in
 * README's "Known limitations (current stage)" section.
 * API/transport failures and cancellation propagate.
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

    // input is a list of fetched statuses, mixed with original toots and translated toots
    // no reblog
    suspend fun filter(statuses: List<Status>, account: CredentialAccount): List<Status> {
        // has footer
        val tootWithFooterIds = statuses.asSequence()
            .filter { it.content?.matchFooterFromContent() != null }
            .map { it.id }.toSet()
        // toot ids that has been replied by translated toots
        // mix of original toots and parent translated toots
        val translatedReplyToIds = statuses.asSequence()
            .filter { it.id in tootWithFooterIds && it.language == targetLangCode }
            .mapNotNull { it.inReplyToId }.toSet()

        val now = clock.instant()
        val wait = cooldown.toJavaDuration()
        val pending = mutableListOf<Status>()
        for (status in statuses.sortedBy { it.editedAt ?: it.createdAt }) {
            currentCoroutineContext().ensureActive()
            // too new
            if (java.time.Duration.between(status.editedAt ?: status.createdAt, now) < wait) continue
            // has footer or already in target lang
            if (status.id in tootWithFooterIds || status.language == targetLangCode) continue
            // has been translated, either fully or partially
            if (status.id in translatedReplyToIds) continue
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
