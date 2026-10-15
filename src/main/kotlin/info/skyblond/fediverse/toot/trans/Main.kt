package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.di.appConfigStore
import info.skyblond.fediverse.toot.trans.di.appModule
import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent
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

    val accountsClient = koin.get<AccountsClient>()
    val statusesPool = koin.get<StatusesPool>()
    val account = koin.get<CredentialAccount>()
    val rateLimitBackoff = koin.get<RateLimitBackoff>()

    val langCode = config.targetLangCode

    val toots = runBlocking {
        // the first scan after boot up, see performInitialScan; runs
        // on the container's shared backoff so every caller on this
        // token backs off alike, see RateLimitBackoff's KDoc
        performInitialScan(
            accountsClient, statusesPool, account, config.initialScanSize,
            rateLimitBackoff = rateLimitBackoff,
            transientMaxRetry = config.scanTransientMaxRetry,
            transientRetryDelay = config.scanTransientRetryDelay,
        )
    }

    val translatedToots = toots.filter { toot ->
        toot.content?.matchFooterFromContent() != null && toot.language == langCode
    }.onEach { // debugging print
        logger.info { "Translated toot: id=${it.id}, lang=${it.language}, time=${it.editedAt ?: it.createdAt}, content: ${it.content}" }
    }

    val originalToots = toots.filter { toot ->
        toot.content?.matchFooterFromContent() == null && toot.language != langCode
    }.onEach { // debugging print
        logger.info { "Original toot: id=${it.id}, lang=${it.language}, time=${it.editedAt ?: it.createdAt}, content: ${it.content}" }
    }

    logger.info { "Original toots: ${originalToots.size}" }
    logger.info { "Translated toots: ${translatedToots.size}" }

}
