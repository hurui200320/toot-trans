package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.di.appConfigStore
import info.skyblond.fediverse.toot.trans.di.appModule
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

private val logger = KotlinLogging.logger("Application")

fun main() {
    val config = appConfigStore()

    // resolve the config outside of the logger lambda:
    // kotlin-logging swallows exceptions thrown inside the lambda,
    // which would turn a missing config into a silent, clean exit
    val mastoHost = config.mastoHost
    logger.info { "Instance host: $mastoHost" }

    val koin = startKoin { modules(appModule(config)) }.koin
    try {
        runBlocking {
            val accountsClient = koin.get<AccountsClient>()
            val statusesPool = koin.get<StatusesPool>()
            val userId = accountsClient.verifyCredentials().let {
                logger.info { "Logged in as ${it.username}, user id: ${it.id}" }
                it.id
            }
            // a boost embeds someone else's toot and carries no text of the
            // owner's own, so there is nothing to translate; filter them
            // out server-side instead of skipping the wrappers downstream
            val fetch1 = accountsClient.getStatuses(userId, 40, excludeReblogs = true)
            statusesPool.seed(fetch1)
            fetch1.forEach {
                logger.info { "Status: ${it.id}, time: ${it.editedAt ?: it.createdAt}, content: ${it.content}" }
            }
            fetch1.lastOrNull()?.let { last ->
                val fetch2 =
                    accountsClient.getStatuses(userId, 40, maxId = last.id, excludeReblogs = true)
                statusesPool.seed(fetch2)
                fetch2.forEach {
                    logger.info { "Status: ${it.id}, time: ${it.editedAt ?: it.createdAt}, content: ${it.content}" }
                }
            }
        }
    } finally {
        // releases the container and everything it holds, see the
        // onClose callbacks in [appModule]
        stopKoin()
    }
}
