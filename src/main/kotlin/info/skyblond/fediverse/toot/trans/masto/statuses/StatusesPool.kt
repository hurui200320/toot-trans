package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Shared, in-memory status lookups for one instance and access token.
 *
 * Misses collect for [batchWindow] from the oldest queued request (not a
 * debounce). One worker calls [StatusesClient.getStatuses], at most
 * [StatusesClient.MAX_IDS_PER_CALL] IDs at a time, with request starts at
 * least [requestInterval] apart. Overflow stays FIFO queued; idle periods
 * do not cause catch-up bursts. This pacing does not reserve budget for
 * other API clients, which share the server's rate-limit buckets.
 *
 * The size-bounded LRU cache expires after insertion, never after access.
 * Omitted IDs resolve to null without caching; callers should avoid repeatedly
 * requesting missing statuses. Other errors fail that batch's callers.
 * 429s suspend the worker until the reset plus [resetMargin]; resets that are
 * missing, unparseable, or further out than [maxRetry] count as unusable
 * headers and use a fixed [fallbackDelay] instead, with no automatic retry
 * of other errors.
 * Cache hits and [seed] remain available during backoff.
 *
 * The worker belongs to [scope]. Call [close] or [shutdown] before waiting
 * for that scope to finish. Individual callers cannot cancel shared work;
 * queued work remains useful for caching even if all its callers cancel.
 * Distinct outstanding IDs remain in an unbounded FIFO queue.
 * Timing uses [timeSource] for elapsed durations and [clock] for server
 * reset timestamps, allowing deterministic tests without wall-clock sleeps.
 */
class StatusesPool(
    private val client: StatusesClient,
    scope: CoroutineScope,
    private val batchWindow: Duration = DEFAULT_BATCH_WINDOW,
    private val requestInterval: Duration = DEFAULT_REQUEST_INTERVAL,
    private val cacheTtl: Duration = DEFAULT_CACHE_TTL,
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
    private val resetMargin: Duration = DEFAULT_RESET_MARGIN,
    private val fallbackDelay: Duration = DEFAULT_FALLBACK_DELAY,
    private val maxRetry: Duration = DEFAULT_MAX_RETRY,
    private val clock: Clock = Clock.systemUTC(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : AutoCloseable {

    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()

    private class Cached(val status: Status, val insertedAt: TimeMark)

    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)

    private class Lookup(val queuedAt: TimeMark) {
        val result = CompletableDeferred<Status?>()
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
        requireValidDuration("requestInterval", requestInterval)
        requireValidDuration("cacheTtl", cacheTtl)
        requireValidDuration("fallbackDelay", fallbackDelay)
        requireValidDuration("maxRetry", maxRetry)
        require(resetMargin.isFinite() && resetMargin >= Duration.ZERO) { "resetMargin must be finite and non-negative" }
        require(maxCacheEntries > 0) { "maxCacheEntries must be positive" }
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
     * Return a status, or null if unavailable to this token. [bypassCache]
     * skips cached results, but still joins any
     * queued/in-flight lookup for the ID and obeys batching and 429 backoff.
     * A successful forced fetch replaces the cached result; one that
     * finds the status gone removes the stale cached entry.
     */
    suspend fun getStatus(statusId: String, bypassCache: Boolean = false): Status? {
        require(statusId.isNotBlank()) { "statusId must not be blank" }
        val result = mutex.withLock {
            worker.ensureActive()
            if (!bypassCache) {
                cache[statusId]?.let {
                    if (it.insertedAt.elapsedNow() < cacheTtl) return it.status
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
     * Supply freshly fetched polling results or newly created statuses.
     * These replace cached values and satisfy outstanding lookups; an older
     * pool request still in flight cannot subsequently overwrite them.
     * Callers must supply current observations, not old cached snapshots.
     */
    suspend fun seed(statuses: Collection<Status>) {
        require(statuses.none { it.id.isBlank() }) { "statuses must not contain blank IDs" }
        mutex.withLock {
            worker.ensureActive()
            statuses.forEach {
                putCached(it.id, it)
                pending.remove(it.id)?.result?.complete(it)
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

    override fun close() {
        worker.cancel()
    }

    /** Cancel the pool and wait for all outstanding lookups to be released. */
    suspend fun shutdown() {
        worker.cancelAndJoin()
    }

    private fun putCached(id: String, status: Status) {
        cache[id] = Cached(status, timeSource.markNow())
        while (cache.size > maxCacheEntries) {
            cache.remove(cache.entries.first().key)
        }
    }

    private suspend fun runWorker() {
        var lastStarted: TimeMark? = null
        while (true) {
            val oldest = mutex.withLock { pending.values.firstOrNull { !it.inFlight } }
            if (oldest == null) {
                wakeup.receive()
                continue
            }
            val collectionWait = batchWindow - oldest.queuedAt.elapsedNow()
            val pacingWait = lastStarted?.let { requestInterval - it.elapsedNow() } ?: Duration.ZERO
            delay(maxOf(collectionWait, pacingWait, Duration.ZERO))
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
                lastStarted?.let { delay(maxOf(requestInterval - it.elapsedNow(), Duration.ZERO)) }
                lastStarted = timeSource.markNow()
                try {
                    val statuses = client.getStatuses(active.keys).associateBy { it.id }
                    mutex.withLock {
                        active.forEach { (id, lookup) ->
                            if (pending[id] === lookup) {
                                val status = statuses[id]
                                if (status != null) putCached(id, status) else cache.remove(id)
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
                        val wait = resetWait(e) ?: fallbackDelay
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

    private fun resetWait(error: MastoApiException): Duration? {
        val raw = error.rateLimitReset ?: return null
        val reset = try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            return null
        }
        val now = clock.instant()
        if (!reset.isAfter(now)) return null
        val wait = java.time.Duration.between(now, reset)
        // Windows can legitimately be long on customized instances, so
        // distance alone does not flag a broken header; [maxRetry] is the
        // sanity line. Beyond it the header is treated as unusable so a
        // pathological timestamp cannot park the worker indefinitely.
        if (wait > java.time.Duration.ofMillis(maxRetry.inWholeMilliseconds)) return null
        return wait.seconds.seconds + (wait.nano / 1_000_000).milliseconds + resetMargin
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
         * the programmatic/test defaults.
         * */
        val DEFAULT_BATCH_WINDOW: Duration = 1.seconds
        val DEFAULT_REQUEST_INTERVAL: Duration = 1.seconds
        val DEFAULT_CACHE_TTL: Duration = 1.minutes
        val DEFAULT_MAX_CACHE_ENTRIES: Int = 1_000

        /** Big margin for a very inaccurate system clock. */
        val DEFAULT_RESET_MARGIN: Duration = 800.milliseconds
        val DEFAULT_FALLBACK_DELAY: Duration = 10.seconds

        /** Sanity bound on reset-based backoff, see `resetWait`. */
        val DEFAULT_MAX_RETRY: Duration = 24.hours
    }
}
