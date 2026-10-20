package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.config.requireValidScanInterval
import info.skyblond.fediverse.toot.trans.di.appConfigStore
import info.skyblond.fediverse.toot.trans.di.appModule
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

val logger = KotlinLogging.logger("Application")

/**
 * Built-in wait between the end of one drain and the next scan. One
 * minute is the cadence the rate-limit budget in `AGENTS.md` assumes
 * (one paging request per minute is about 5% of the 15-minute paging
 * bucket); a deployment can override it via `scanInterval`.
 *
 * Single source of truth, same pattern as `Scan.kt`'s transient
 * defaults: `DefaultConfigProvider` reuses it, so the deploy default
 * cannot drift apart from the programmatic/test default.
 * */
val DEFAULT_SCAN_INTERVAL: Duration = 1.minutes

fun main() {
    val config = appConfigStore()
    // validated before anything else starts, naming the setting
    val scanInterval = requireValidScanInterval("Config `scanInterval`", config.scanInterval)

    val koin = startKoin { modules(appModule(config)) }.koin
    try {
        // resolved on the main thread: `CredentialAccount` blocks on
        // network IO at first resolution, see `di/AppModule.kt`
        val accountsClient = koin.get<AccountsClient>()
        val account = koin.get<CredentialAccount>()
        val rateLimitBackoff = koin.get<RateLimitBackoff>()
        val pendingQueue = koin.get<PendingQueue>()
        val processor = koin.get<TranslationProcessor>()
        val scope = koin.get<CoroutineScope>()

        // One scan strategy for the boot and the loop: the bounded
        // newest-window scan while no rolling cursor exists yet, the
        // unbounded incremental scan right after the cursor otherwise
        // (see Scan.kt for both).
        suspend fun scanOnce(cursor: String?): List<FetchedStatus> =
            if (cursor == null) {
                performInitialScan(
                    accountsClient, account, config.initialScanSize,
                    rateLimitBackoff = rateLimitBackoff,
                    transientMaxRetry = config.transientMaxRetry,
                    transientRetryDelay = config.transientRetryDelay,
                )
            } else {
                performIncrementalScan(
                    accountsClient, account, cursor,
                    rateLimitBackoff = rateLimitBackoff,
                    transientMaxRetry = config.transientMaxRetry,
                    transientRetryDelay = config.transientRetryDelay,
                )
            }

        // The loop lives on the application scope instead of a runBlocking
        // on the main thread, so the shutdown hook can cancel it and wait
        // for the drain to stop before tearing the container down (whose
        // close callbacks join the pool worker). `async` rather than
        // `launch`: a failing drain must be rethrown by `await` below
        // (fail-fast), not just logged and swallowed.
        val loop = scope.async {
            pendingQueue.feed(scanOnce(null))

            while (true) {
                // Returns when the window is empty or the drain is blocked;
                // either way the next scan waits out the interval. Skipping
                // the wait on a blocked drain would only re-fetch the same
                // un-cooled copies and stop at the same head again.
                processor.processQueue()

                delay(scanInterval)

                // Resume right after the rolling cursor. Without one, the
                // bounded scan cannot guarantee it still covers the queued
                // head (the window slides as new toots arrive), so that head
                // is dropped to establish the cursor; see
                // PendingQueue.dropHeadAndRecordCursor.
                var cursor = pendingQueue.cursor()
                if (cursor == null) {
                    // the returned id is the sacrificed head: the entry is
                    // dropped without being processed, so say so loudly
                    cursor = pendingQueue.dropHeadAndRecordCursor()
                    if (cursor != null) {
                        logger.warn {
                            "Establishing the rolling cursor by dropping toot $cursor " +
                                    "without processing it; it will not be retried"
                        }
                    }
                }

                // A null cursor here means the window was empty and nothing
                // was ever processed: the bounded scan discovers the newest
                // window again, exactly like the boot scan.
                pendingQueue.feed(scanOnce(cursor))
            }
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            // stop the loop first, then the container: the reverse order
            // would close the pool under a running drain
            runBlocking { loop.cancelAndJoin() }
            stopKoin()
        })

        runBlocking {
            try {
                loop.await()
            } catch (_: CancellationException) {
                // only the shutdown hook cancels the loop; the JVM is exiting
            }
        }
    } finally {
        stopKoin()
    }
}
