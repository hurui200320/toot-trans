package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Deletes statuses through [client], one at a time, and drops each
 * deleted (or already-gone) status from [pool] and [queue]. The queue
 * drop keeps a deleted segment from lingering as chain material and
 * from pinning the cursor, see [PendingQueue.drop].
 *
 * The deletion order is the caller's: [delete] removes exactly [segments]
 * in the given order, so the leaf-first `obsolete` list of
 * [TranslationChainState.NeedsTranslation] must be passed as is, never
 * re-sorted — see [findTranslationChainState] for the order's rationale.
 * A failure other than a 429 or an already-gone 404 stops the run
 * (fail fast) instead of continuing with shallower parents, which would
 * leave the failed status pointing at a deleted parent.
 *
 * The instance's DELETE budget is small (30 requests / 30 minutes per
 * account by default, on top of the shared token and account buckets),
 * but request starts are not paced: like [StatusesCachePool]'s worker, a 429
 * waits for the instance's `X-RateLimit-Reset` via [rateLimitBackoff]
 * (see [RateLimitBackoff] for the wait computation) and then retries the
 * same status, without bound — the instance says when we may proceed,
 * and fixed wall-clock windows make spacing requests save no budget.
 *
 * Cache coherence: after a DELETE that returned — success or an
 * already-gone 404 — the status is dropped via [StatusesCachePool.invalidate],
 * which also cancels its outstanding lookups, so neither a cached copy
 * nor a pre-deletion in-flight response can serve a status that is
 * already gone. The invalidation runs [NonCancellable], so a cancellation
 * arriving between the DELETE returning and the invalidation cannot skip
 * it and leave a stale copy behind. A cancellation while the DELETE is
 * still in flight is outside this guarantee: whether the instance applied
 * it is unknown, and the stale entry then lives until the cache TTL
 * expires.
 * */
class StatusesDeleter(
    private val client: StatusesClient,
    private val pool: StatusesCachePool,
    private val queue: PendingQueue,
    private val rateLimitBackoff: RateLimitBackoff = RateLimitBackoff(),
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Delete [segments] one by one, in the given (leaf-first) order.
     * Blank ids fail fast before anything is deleted; an empty list is a
     * no-op.
     *
     * 429s back off via [rateLimitBackoff] and retry the same status; a
     * 404 counts as success because the status is already gone, which is
     * the goal state; every other error propagates immediately, leaving
     * the not yet deleted statuses in place for the next scan to retry.
     * */
    suspend fun delete(segments: List<Status>) {
        require(segments.none { it.id.isBlank() }) { "segments must not contain blank ids" }
        segments.forEach { segment ->
            deleteOne(segment.id)
        }
    }

    private suspend fun deleteOne(statusId: String) {
        logger.info { "Deleting status: id=$statusId" }
        while (true) {
            try {
                client.deleteStatus(statusId)
                break
            } catch (e: MastoApiException) {
                when (e.httpCode) {
                    429 -> {
                        val wait = rateLimitBackoff.waitDuration(e)
                        logger.warn {
                            "Rate limited deleting $statusId, backing off $wait " +
                                    "(reset: ${e.rateLimitReset ?: "no usable header"})"
                        }
                        delay(wait)
                    }
                    // already gone: the goal state, nothing left to delete
                    404 -> break
                    else -> throw e
                }
            }
        }
        logger.info { "Deleted status: id=$statusId" }
        // the status is gone on the instance either way, so a canceled
        // caller must not leave it cached here
        withContext(NonCancellable) {
            pool.invalidate(statusId)
            queue.drop(statusId)
        }
    }
}
