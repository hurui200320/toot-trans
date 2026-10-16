package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.di.appConfigStore
import info.skyblond.fediverse.toot.trans.di.appModule
import info.skyblond.fediverse.toot.trans.filter.TootFilter
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
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
        val statusesPool = koin.get<StatusesPool>()
        val filter = koin.get<TootFilter>()
        val account = koin.get<CredentialAccount>()
        val rateLimitBackoff = koin.get<RateLimitBackoff>()

        runBlocking {
            // The scan shares the pool's backoff policy; see RateLimitBackoff.
            val toots = performInitialScan(
                accountsClient, statusesPool, account, config.initialScanSize,
                rateLimitBackoff = rateLimitBackoff,
                transientMaxRetry = config.scanTransientMaxRetry,
                transientRetryDelay = config.scanTransientRetryDelay,
            )

            val pending = filter.filter(toots, account)
            logger.info { "Pending translations: ${pending.size}" }
            pending.forEach { toot ->
                // TODO: LLM translation and posting.
                logger.info { "Pending translation: id=${toot.id}, lang=${toot.language}" }
            }
        }
    } finally {
        stopKoin()
    }
}
