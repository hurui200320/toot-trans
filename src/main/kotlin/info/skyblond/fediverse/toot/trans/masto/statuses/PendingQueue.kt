package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.config.requireValidTranslationMaxAge
import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.toJavaDuration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.util.TreeSet

/**
 * The pending work window: the originals awaiting translation, plus the
 * target-language translation segments needed to reconstruct their chains.
 *
 * [feed] runs the entrance eligibility over one scan result and inserts the
 * survivors; [next] hands out the oldest entry; [drop] removes an entry the
 * caller has finished with. The queue head doubles as the rolling cursor:
 * dropping the head records its id ([cursor]) so a later scan can resume
 * right after it. Entries blocked for now (cooling down, see `Cooldown.kt`)
 * are deliberately kept by the caller, so the cursor never advances past
 * something unprocessed. That rule is load-bearing, not bookkeeping: a
 * later scan resumes right after the cursor, and a cooling copy can only
 * ever pass [Cooldown] through a later fetch, because the recorded fetch
 * time never changes — so an entry the cursor skipped could never be
 * processed.
 *
 * Ordering is by [Status.createdAt] with [Status.id] breaking ties, never
 * by edit time: creation order keeps every parent ahead of its replies,
 * which is the order the translation stage needs to build the ancestry
 * context, and an edit cannot reorder the window. The cooldown still reads
 * `editedAt ?: createdAt`.
 *
 * The max-age cutoff ([maxAge]) bounds the entrance by age: the scan
 * window is by count (`initialScanSize`), so a big one can reach days
 * back, and the cutoff keeps that old tail out of the work window. An
 * original whose version in hand was fetched more than [maxAge] after
 * its effective time `editedAt ?: createdAt` is skipped by [feed] — an
 * edit counts as the toot becoming current again. The check reads
 * [FetchedStatus.fetchedAt] instead of wall clock, so the decision
 * belongs to the observation in hand, like the cooldown; the boundary
 * is inclusive, and [Duration.ZERO] disables the cutoff. Rejected
 * originals are still seeded into the pool, and translated segments are
 * never subject to the cutoff, so both keep serving as chain context.
 * [DEFAULT_MAX_AGE] is the built-in default, reused by
 * `DefaultConfigProvider` so the deploy default cannot drift apart from
 * the programmatic/test default.
 *
 * No state is persisted: a restart rebuilds the window from a fresh scan.
 * All operations suspend on one [Mutex], so the queue can be fed and
 * drained from different coroutines.
 * */
class PendingQueue(
    private val ourAccountId: String,
    private val targetLangCode: String,
    private val cachePool: StatusesCachePool,
    private val maxAge: Duration = DEFAULT_MAX_AGE,
) {
    private val toots: TreeSet<FetchedStatus> = TreeSet<FetchedStatus>(
        compareBy<FetchedStatus> { it.status.createdAt }
            // only compare by id to break a tie
            // so we don't accidentally delete the toots with same create time
            .thenComparing { it.status.id }
    )

    // footer of each queued status; null marks an original (no valid footer),
    // so [isTranslation] can tell the two roles apart without re-parsing
    private val footerMap: HashMap<String, Footer?> = hashMapOf()

    private var cursor: String? = null

    // mutex to protect read and write
    private val mutex = Mutex()

    init {
        require(ourAccountId.isNotBlank()) { "ourAccountId must not be blank" }
        require(targetLangCode.isNotBlank()) { "targetLangCode must not be blank" }
        requireValidTranslationMaxAge("maxAge", maxAge)
    }

    /**
     * Feed one scan result, in any order and possibly overlapping earlier
     * feeds, and insert the statuses this deployment works on:
     *
     * - originals (no valid footer, see [Footer]): skip ones already in
     *   [targetLangCode], ones already older than [maxAge] at fetch time
     *   (the class KDoc carries the timing contract), ones whose reply
     *   chain does not qualify (every ancestor must be a reply to us,
     *   ending at a root, see [checkReplyChain]), and ones blank in both
     *   body and content warning;
     * - translated toots (valid footer): keep the [targetLangCode] ones as
     *   chain material, skip the rest. The max-age cutoff never applies
     *   here: segments are context, not work.
     *
     * Every fed status is seeded into [cachePool] first, so ancestry reads
     * during qualification resolve locally instead of hitting the API. The
     * seeding follows the fed order: one feed larger than the pool's
     * `maxCacheEntries` evicts the oldest entries first — the ancestors the
     * earliest qualifications read — and those walks fall back to the pool's
     * batched fetch-on-miss (correct, but extra API traffic). Accepted
     * trade-off: one seeding path for the pool; the boot scan's default size
     * stays well below the cache cap.
     *
     * Re-feeding an id replaces the queued copy (latest observation wins)
     * without touching the cursor — the entry is still pending. A fed copy
     * that no longer qualifies is dropped instead, so a stale queued copy
     * cannot linger; when it was the head, the drop records the cursor, see
     * [drop].
     *
     * It is recommended to scan from [cursor] to the current head when a
     * cursor exists, so the window covers everything newer than what was
     * already processed.
     * */
    suspend fun feed(
        statuses: List<FetchedStatus>
    ) {
        // feed into cache pool
        cachePool.seed(statuses)
        // filter and insert to pending queue
        statuses.forEach { fetched ->
            val status = fetched.status
            val footer = status.content?.matchFooterFromContent()

            if (isEligible(fetched, footer)) { // accepted, this is addOrReplace
                mutex.withLock {
                    // delete same id first; unlike [unsafeDrop], this keeps the
                    // cursor: the entry stays pending, so the next scan must
                    // keep covering it
                    toots.filter { it.status.id == status.id }.forEach { toots.remove(it) }
                    toots.add(fetched)
                    footerMap[status.id] = footer
                }
            } else { // rejected, should remove it in case this is an update
                mutex.withLock { unsafeDrop(status.id) }
            } // note we do not merge mutex.withLock because isEligible may hit API
        }
    }

    /**
     * Whether one fed status enters the work window; the checks are
     * documented on [feed]. The reply-chain walk reads [cachePool], the
     * max-age check reads [maxAge].
     * */
    private suspend fun isEligible(fetched: FetchedStatus, footer: Footer?): Boolean {
        val status = fetched.status
        if (footer != null) { // translated toot
            // not in target lang, skip
            if (status.language != targetLangCode) return false
        } else { // original toot
            // already in target lang, skip
            if (status.language == targetLangCode) return false
            // is reply but not to ourselves, skip
            if (status.inReplyToId != null && status.inReplyToAccountId != ourAccountId) return false
            // already older than the cutoff when fetched, skip; the
            // class KDoc carries the timing contract
            if (!checkMaxAge(fetched)) return false
            // body and content warning is all blank, skip
            val body = Jsoup.parseBodyFragment(status.content.orEmpty()).body().text()
            if (body.isBlank() && status.spoilerText.isNullOrBlank()) return false
            // check reply chain, has replies to others, skip
            // this one may hit the API, so the local checks above run first
            if (!checkReplyChain(status)) return false
        }
        // accepted
        return true
    }

    /**
     * Whether [fetched] is young enough for the max-age cutoff; the class
     * KDoc documents the timing contract.
     * */
    private fun checkMaxAge(fetched: FetchedStatus): Boolean {
        // zero disables the cutoff, like the other policy durations
        if (maxAge == Duration.ZERO) return true
        val effectiveTime = fetched.status.editedAt ?: fetched.status.createdAt
        return java.time.Duration.between(effectiveTime, fetched.fetchedAt) <= maxAge.toJavaDuration()
    }

    private fun unsafeDrop(statusId: String) {
        // dropping the head sets the cursor, so next scan will skip this
        // and start with next; an id that is not queued (already gone) is
        // a no-op
        if (toots.firstOrNull()?.status?.id == statusId) {
            cursor = statusId
        }
        toots.filter { it.status.id == statusId }.forEach { toots.remove(it) }
        footerMap.remove(statusId)
    }

    /**
     * Remove [statusId] from the work window without deleting it on the
     * instance. Callers must only drop entries they actually finished with:
     * a blocked entry must stay queued so the head, and with it [cursor],
     * cannot move past it. Dropping an id that is not queued is a no-op.
     * */
    suspend fun drop(statusId: String) {
        mutex.withLock { unsafeDrop(statusId) }
    }

    /**
     * The id of the last dropped head, or null before any head was dropped.
     * A later scan resumes right after it (e.g. as a `since_id`) to avoid
     * re-fetching already processed toots.
     * */
    suspend fun cursor(): String? = mutex.withLock { cursor }

    /**
     * Whether the window holds an entry for [next] to hand out.
     * */
    suspend fun hasNext(): Boolean = mutex.withLock { toots.isNotEmpty() }

    /**
     * The oldest entry in the window. Must only be called while [hasNext]
     * holds; [drop] it once processed.
     * */
    suspend fun next(): FetchedStatus {
        return mutex.withLock { toots.first() }
    }

    /**
     * Whether the queued status [statusId] is one of our translation
     * segments (a target-language toot carrying a footer) rather than an
     * original awaiting translation. Translation segments are chain
     * material: they are reconstructed through the original's
     * [findTranslationChainState] and are never translation candidates
     * themselves.
     *
     * Throws [NoSuchElementException] when [statusId] is not queued, so
     * "not in the window" cannot be mistaken for "not a translation".
     * */
    suspend fun isTranslation(statusId: String): Boolean = mutex.withLock {
        if (toots.none { it.status.id == statusId }) {
            throw NoSuchElementException("no queued status with id $statusId")
        }
        footerMap[statusId] != null
    }

    /**
     * Find the translation chain state of [status] by classifying the
     * queued window merged with the pool's cached observations
     * ([StatusesCachePool.dump]): one copy per id, the newer
     * [FetchedStatus.fetchedAt] winning, a tie keeping the queued copy.
     *
     * The merge matters because the queue is a work list, not the source
     * of truth: a drop only removes an entry from the window (see
     * [drop]), so a status the caller has already processed still exists
     * on the instance and must stay visible through the pool, and a
     * queued copy is refreshed whenever the pool holds a newer fetch of
     * the same id. The classification rules themselves live in the pure
     * [findTranslationChainState].
     * */
    suspend fun findTranslationChainState(
        status: Status
    ): TranslationChainState {
        val newestById = LinkedHashMap<String, FetchedStatus>()
        // the window copy comes first, so it wins a fetched-at tie
        mutex.withLock {
            toots.forEach { newestById[it.status.id] = it }
        }
        cachePool.dump().forEach { fetched ->
            val known = newestById[fetched.status.id]
            if (known == null || fetched.fetchedAt > known.fetchedAt) {
                newestById[fetched.status.id] = fetched
            }
        }
        return findTranslationChainState(status.id, newestById.values.map { it.status }, targetLangCode)
    }

    /**
     * Qualify the entire ancestry of [status], not just the immediate
     * parent: every reply must name us as its parent account, ending at a
     * non-reply. A reply with no parent-account metadata is inconclusive,
     * not a self-reply.
     *
     * Missing ancestors and cycles fail closed. There is no permissive
     * depth limit. Reads use [cachePool]; transport/API failures and
     * cancellation propagate.
     * */
    private suspend fun checkReplyChain(status: Status): Boolean {
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
            if (current.inReplyToAccountId != ourAccountId) return false

            val parentId = current.inReplyToId
            // detect infinite loop
            if (!visited.add(parentId)) return false
            // find next parent loop
            current = cachePool.getStatus(parentId)?.status ?: return false
        }
    }

    companion object {
        /**
         * Built-in max age: one day, enough to catch up a normal gap
         * while keeping a fresh boot from replying to toots from days
         * ago. Single source of truth, same pattern as [Cooldown]'s
         * companion object: the config package's default provider reuses
         * it, so the deploy default cannot drift apart from the
         * programmatic/test default.
         * */
        val DEFAULT_MAX_AGE: Duration = 1.days
    }
}
