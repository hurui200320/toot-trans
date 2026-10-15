package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.config.ConfigStore
import info.skyblond.fediverse.toot.trans.config.DefaultConfigProvider
import info.skyblond.fediverse.toot.trans.config.EnvConfigProvider
import info.skyblond.fediverse.toot.trans.config.file.PropertiesConfigProvider
import info.skyblond.fediverse.toot.trans.masto.MastoApiClient
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.accounts.OkHttpAccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.OkHttpStatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File

private val logger = KotlinLogging.logger("Application")

fun main() {
    val config = ConfigStore(
        listOf(
            EnvConfigProvider(),
            PropertiesConfigProvider(
                File(
                    System.getenv("CONFIG_PROPERTIES_PATH")
                        ?: "./config.properties"
                )
            ),
            DefaultConfigProvider()
        )
    )

    // resolve the config outside of the logger lambda:
    // kotlin-logging swallows exceptions thrown inside the lambda,
    // which would turn a missing config into a silent, clean exit
    val mastoHost = config.mastoHost
    logger.info { "Instance host: $mastoHost" }

    val okHttp = OkHttpClient.Builder().build()
    val mastoApi = MastoApiClient(mastoHost, config.accessToken, okHttp)

    val accountsClient: AccountsClient = OkHttpAccountsClient(mastoApi)
    val statusesClient: StatusesClient = OkHttpStatusesClient(mastoApi)

    runBlocking {
        val statusesPool = StatusesPool(
            statusesClient, this,
            batchWindow = config.poolBatchWindow,
            requestInterval = config.poolRequestInterval,
            cacheTtl = config.poolCacheTtl,
            maxCacheEntries = config.poolMaxCacheEntries,
            resetMargin = config.poolResetMargin,
            fallbackDelay = config.poolFallbackDelay,
            maxRetry = config.poolMaxRetry,
        )
        try {
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
                val fetch2 = accountsClient.getStatuses(userId, 40, maxId = last.id, excludeReblogs = true)
                statusesPool.seed(fetch2)
                fetch2.forEach {
                    logger.info { "Status: ${it.id}, time: ${it.editedAt ?: it.createdAt}, content: ${it.content}" }
                }
            }
        } finally {
            logger.info { "Statuses pool shutting down..." }
            statusesPool.shutdown()
        }
    }
}
