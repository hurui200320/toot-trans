package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.di.appConfigStore
import info.skyblond.fediverse.toot.trans.di.appModule
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

val logger = KotlinLogging.logger("Application")

fun main() {
    val config = appConfigStore()

    val koin = startKoin { modules(appModule(config)) }.koin
    Runtime.getRuntime().addShutdownHook(Thread { stopKoin() })

    try {
        val accountsClient = koin.get<AccountsClient>()
        val account = koin.get<CredentialAccount>()
        val rateLimitBackoff = koin.get<RateLimitBackoff>()
        val pendingQueue = koin.get<PendingQueue>()
        val processor = koin.get<TranslationProcessor>()

        runBlocking {
            // The scan uses the shared backoff policy; see RateLimitBackoff.
            val fetches = performInitialScan(
                accountsClient, account, config.initialScanSize,
                rateLimitBackoff = rateLimitBackoff,
                transientMaxRetry = config.transientMaxRetry,
                transientRetryDelay = config.transientRetryDelay,
            )

            pendingQueue.feed(fetches)
            processor.processQueue()

            // The periodic cursor-based scan is a separate milestone: the
            // in-memory window is rebuilt by the boot scan on the next run,
            // so this process currently exits after one drain.
            if (pendingQueue.hasNext()) {
                logger.info { "Drain stopped with entries still pending, exiting; the next run retries them" }
            } else {
                logger.info { "Queue drained, exiting" }
            }
        }
    } finally {
        stopKoin()
    }
}
