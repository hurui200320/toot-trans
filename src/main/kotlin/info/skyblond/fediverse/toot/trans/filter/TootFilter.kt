package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import info.skyblond.fediverse.toot.trans.masto.statuses.TranslationChainState
import info.skyblond.fediverse.toot.trans.masto.statuses.checkReplyChain
import info.skyblond.fediverse.toot.trans.masto.statuses.findTranslationChainState
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
 * `masto/statuses/TranslationChain.kt` — for the candidate's own chain.
 * The ancestor-translation prerequisite for reply chains is part of
 * eligibility, see [filter].
 */
class TootFilter(
    private val pool: StatusesCachePool,
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
     *
     * Translated-ancestry prerequisite: an original is eligible only when
     * every in-scan ancestor that itself needs translation already has a
     * complete chain ([TranslationChainState.Translated]) at scan time.
     * The LLM history pairs an ancestor's original (user turn) with its
     * translation (assistant turn), so a child must not overtake an
     * untranslated ancestor — for example when an edit reset the
     * ancestor's cooldown and reordered the effective times. Ancestors
     * that need no translation (target language, footer posts, blank
     * body/CW) never block, and ancestors outside the scan end the walk:
     * they are assumed settled, because their chains cannot be resolved in
     * memory and they are outside the context design's window. This
     * deliberately serializes chains: a fresh thread advances one
     * generation per run. The ancestor classification adds no API calls;
     * it is memoized across candidates.
     *
     * API/transport failures and cancellation propagate.
     * */
    suspend fun filter(statuses: List<Status>, account: CredentialAccount): List<Status> {
        val now = clock.instant()
        val wait = cooldown.toJavaDuration()
        val statusesMap = statuses.associateBy { it.id }
        val chainStates = mutableMapOf<String, TranslationChainState>()

        val pending = mutableListOf<Status>()
        for (status in statuses.sortedBy { it.editedAt ?: it.createdAt }) {
            currentCoroutineContext().ensureActive()
            // too new
            if (java.time.Duration.between(status.editedAt ?: status.createdAt, now) < wait) continue
            // should be translated (common checks)
            if (!requiresTranslation(status)) continue
            // reply chain is not valid
            if (!pool.checkReplyChain(status, account)) continue
            // ancestors must be translated first
            if (!ancestorsTranslated(status, statusesMap, chainStates)) continue

            // accepted
            pending.add(status)
        }
        return pending
    }

    /**
     * Whether [status] is a toot this deployment would translate: not
     * already in the target language, not a translation reply (valid
     * footer), and not blank in both body and content warning. Cooldown
     * and ancestry are checked by the caller.
     * */
    private fun requiresTranslation(status: Status): Boolean {
        // already in target lang
        if (status.language == targetLangCode) return false
        // has footer
        if (status.content?.matchFooterFromContent() != null) return false
        // body and content warning is all blank
        val body = Jsoup.parseBodyFragment(status.content.orEmpty()).body().text()
        return body.isNotBlank() || !status.spoilerText.isNullOrBlank()
    }

    /**
     * The translated-ancestry prerequisite, see the [filter] KDoc: walk the
     * parent chain inside the scan and require every ancestor that
     * [requiresTranslation] to already be translated. Ancestors outside
     * the scan end the walk. [chainStates] memoizes the classification
     * across candidates.
     * */
    private fun ancestorsTranslated(
        status: Status,
        statusesMap: Map<String, Status>,
        chainStates: MutableMap<String, TranslationChainState>,
    ): Boolean {
        val visited = hashSetOf(status.id)
        var ancestor = statusesMap[status.inReplyToId]
        while (ancestor != null) {
            // checkReplyChain already rejects cycles; fail closed anyway
            if (!visited.add(ancestor.id)) return false
            if (requiresTranslation(ancestor)) {
                val state = chainStates.getOrPut(ancestor.id) {
                    findTranslationChainState(ancestor.id, statusesMap.values, targetLangCode)
                }
                if (state !is TranslationChainState.Translated) return false
            }
            ancestor = statusesMap[ancestor.inReplyToId]
        }
        return true
    }

    companion object {
        /** Single source of truth for the deploy and programmatic cooldown default. */
        val DEFAULT_COOLDOWN: Duration = 3.minutes
    }
}
