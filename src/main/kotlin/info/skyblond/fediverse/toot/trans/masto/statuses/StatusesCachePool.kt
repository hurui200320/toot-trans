package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Shared, in-memory fetched-status lookups for one instance and access
 * token. Every entry keeps its [FetchedStatus] fetch time, so callers receive
 * the observation time alongside the status.
 *
 * Misses collect for [batchWindow] from the oldest queued request (not a
 * debounce). One worker calls [StatusesClient.getStatuses], at most
 * [StatusesClient.MAX_IDS_PER_CALL] IDs at a time. Ready batches are sent
 * back-to-back, with overflow staying FIFO queued; request starts are not
 * paced, because the instance's rate-limit windows are fixed, so spacing
 * requests saves no budget. Exhausting the shared buckets surfaces as 429
 * backoff (see below), which adapts to whatever limits the instance
 * actually enforces. The pool does not reserve budget for other API
 * clients, which share the server's rate-limit buckets.
 *
 * The size-bounded LRU cache expires after insertion, never after access.
 * Omitted IDs resolve to null without caching; callers should avoid repeatedly
 * requesting missing statuses. 429s suspend the worker for the wait computed
 * by the shared [rateLimitBackoff] — see [info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff] for the
 * reset-header semantics and its fallback — then retry the batch; other
 * errors fail that batch's callers with no automatic retry.
 * Cache hits and [seed] remain available during backoff.
 *
 * The worker belongs to [scope]. Call [close] before waiting for that
 * scope to finish. Individual callers cannot cancel shared work;
 * queued work remains useful for caching even if all its callers cancel.
 * Distinct outstanding IDs remain in an unbounded FIFO queue.
 * Timing uses [timeSource] for elapsed durations, allowing deterministic
 * tests without wall-clock sleeps.
 */
class StatusesCachePool(
    private val client: StatusesClient,
    scope: CoroutineScope,
    private val batchWindow: Duration = DEFAULT_BATCH_WINDOW,
    private val cacheTtl: Duration = DEFAULT_CACHE_TTL,
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
    private val rateLimitBackoff: RateLimitBackoff = RateLimitBackoff(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {

    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()

    private class Cached(val fetched: FetchedStatus, val insertedAt: TimeMark)

    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)

    private class Lookup(val queuedAt: TimeMark) {
        val result = CompletableDeferred<FetchedStatus?>()
        var inFlight = false
    }

    private val pending = LinkedHashMap<String, Lookup>()

    /**
     * Channel as a wake-up signal for worker.
     *
     * When there are no pending requests, the worker will be suspended by [Channel.receive].
     * When we have new requests, we wake up the worker by feeding a [Unit] into the channel,
     * so the worker can resume and start processing pending requests.
     *
     * This ensures we don't waste CPU cycles when idle, and don't wait fixed periods before
     * processing new requests.
     * */
    private val wakeup = Channel<Unit>(Channel.CONFLATED)

    init {
        requireValidDuration("batchWindow", batchWindow)
        requireValidDuration("cacheTtl", cacheTtl)
        require(maxCacheEntries > 0) { "maxCacheEntries must be positive" }

        logger.info {
            "StatusesCachePool initialized with " +
                    "batchWindow=$batchWindow, " +
                    "cacheTtl=$cacheTtl, " +
                    "maxCacheEntries=$maxCacheEntries"
        }
    }

    // Enter try/finally before exposing the pool so immediate cancellation cannot skip cleanup.
    // With no pending lookups at construction, startup suspends at wakeup.receive().
    private val worker = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            runWorker()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    pending.values.forEach { it.result.cancel() }
                    pending.clear()
                    cache.clear()
                    wakeup.close()
                }
            }
        }
    }

    /**
     * Return a status with the instant it was fetched ([FetchedStatus]),
     * or null if unavailable to this token. [bypassCache] skips cached
     * results, but still joins any
     * queued/in-flight lookup for the ID and obeys batching and 429 backoff.
     * A successful forced fetch replaces the cached result; one that
     * finds the status gone removes the stale cached entry.
     */
    suspend fun getStatus(statusId: String, bypassCache: Boolean = false): FetchedStatus? {
        require(statusId.isNotBlank()) { "statusId must not be blank" }
        val result = mutex.withLock {
            worker.ensureActive()
            if (!bypassCache) {
                cache[statusId]?.let {
                    if (it.insertedAt.elapsedNow() < cacheTtl) return it.fetched
                    cache.remove(statusId)
                }
            }
            pending[statusId]?.result ?: run {
                val lookup = Lookup(timeSource.markNow())
                pending[statusId] = lookup
                wakeup.trySend(Unit)
                lookup.result
            }
        }
        return result.await()
    }

    /**
     * Snapshot of every cached entry, for callers that reconcile the
     * pool against another status source (e.g. merge by newest
     * [FetchedStatus.fetchedAt] on the same ID). Entries whose TTL has
     * elapsed are included too, because the cache expires lazily; later
     * pool writes do not affect the returned list.
     */
    suspend fun dump(): List<FetchedStatus> = mutex.withLock {
        cache.values.map { it.fetched }
    }

    /**
     * Supply freshly fetched polling results or newly created statuses,
     * each carrying the instant it was observed ([FetchedStatus]).
     * These replace cached values and satisfy outstanding lookups; an older
     * pool request still in flight cannot subsequently overwrite them.
     * Callers must supply current observations, not old cached snapshots.
     */
    suspend fun seed(statuses: Collection<FetchedStatus>) {
        require(statuses.none { it.status.id.isBlank() }) { "statuses must not contain blank IDs" }
        mutex.withLock {
            worker.ensureActive()
            statuses.forEach {
                putCached(it)
                pending.remove(it.status.id)?.result?.complete(it)
            }
        }
    }

    /**
     * Forget a status after a successful deletion or known external change.
     * Cancel outstanding lookups for this ID so a pre-invalidation response
     * cannot repopulate the cache. A subsequent lookup queues a new fetch.
     */
    suspend fun invalidate(statusId: String) {
        require(statusId.isNotBlank()) { "statusId must not be blank" }
        mutex.withLock {
            worker.ensureActive()
            cache.remove(statusId)
            pending.remove(statusId)?.result?.cancel(CancellationException("Status invalidated"))
        }
    }

    /**
     * Cancel the worker and block until its cleanup has released all outstanding
     * lookups. This does not drain requests or cancel the supplied scope.
     * Repeated and concurrent calls are safe.
     *
     * Called by Koin during application teardown. The worker's dispatcher must
     * remain able to progress while the calling thread is blocked; do not call
     * this from the worker itself or from its sole dispatcher thread.
     */
    override fun close() {
        runBlocking {
            worker.cancelAndJoin()
        }
    }

    private fun putCached(fetched: FetchedStatus) {
        cache[fetched.status.id] = Cached(fetched, timeSource.markNow())
        while (cache.size > maxCacheEntries) {
            cache.remove(cache.entries.first().key)
        }
    }

    private suspend fun runWorker() {
        while (true) {
            val oldest = mutex.withLock { pending.values.firstOrNull { !it.inFlight } }
            if (oldest == null) {
                wakeup.receive()
                continue
            }
            val collectionWait = batchWindow - oldest.queuedAt.elapsedNow()
            delay(maxOf(collectionWait, Duration.ZERO))
            val batch = mutex.withLock {
                pending.entries.filter { !it.value.inFlight }
                    .take(StatusesClient.MAX_IDS_PER_CALL)
                    .associate { it.key to it.value.also { lookup -> lookup.inFlight = true } }
            }
            if (batch.isEmpty()) continue

            while (true) {
                // seed/invalidate may have resolved a batch while we backed off.
                val active =
                    mutex.withLock { batch.filter { (id, lookup) -> pending[id] === lookup } }
                if (active.isEmpty()) break
                try {
                    val statuses = client.getStatuses(active.keys).associateBy { it.status.id }
                    mutex.withLock {
                        active.forEach { (id, lookup) ->
                            if (pending[id] === lookup) {
                                val status = statuses[id]
                                if (status != null) putCached(status) else cache.remove(id)
                                pending.remove(id)
                                lookup.result.complete(status)
                            }
                        }
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is MastoApiException && e.httpCode == 429) {
                        val wait = rateLimitBackoff.waitDuration(e)
                        logger.warn { "Rate limited fetching ${active.size} statuses, backing off $wait" }
                        delay(wait)
                    } else {
                        mutex.withLock {
                            active.forEach { (id, lookup) ->
                                if (pending[id] === lookup) {
                                    pending.remove(id)
                                    lookup.result.completeExceptionally(e)
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * Shared duration check for the `init` block; the [name] parameter
     * makes each error name the offending setting, which surfaces in
     * config errors (e.g. `poolBatchWindow=0s`).
     * */
    private fun requireValidDuration(name: String, value: Duration) {
        require(value.isFinite() && value.inWholeMilliseconds > 0) { "$name must be finite and at least 1ms" }
    }

    companion object {
        /**
         * Built-in defaults for the optional constructor parameters. They are
         * the single source of truth: the config package's default provider
         * reuses them, so the app's deploy defaults cannot drift apart from
         * the programmatic/test defaults. The 429 backoff defaults live in
         * [RateLimitBackoff]'s companion object for the same reason.
         * */
        val DEFAULT_BATCH_WINDOW: Duration = 1.seconds
        val DEFAULT_CACHE_TTL: Duration = 1.minutes
        val DEFAULT_MAX_CACHE_ENTRIES: Int = 1_000
    }
}
