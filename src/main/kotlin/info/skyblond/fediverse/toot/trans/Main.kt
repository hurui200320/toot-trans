package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.di.appConfigStore
import info.skyblond.fediverse.toot.trans.di.appModule
import info.skyblond.fediverse.toot.trans.llm.client.ChatClient
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesDeleter
import info.skyblond.fediverse.toot.trans.masto.statuses.TranslationChainState
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
        // placeholder here
        val llmClient = koin.get<ChatClient>()
        val accountsClient = koin.get<AccountsClient>()
        val deleter = koin.get<StatusesDeleter>()
        val account = koin.get<CredentialAccount>()
        val rateLimitBackoff = koin.get<RateLimitBackoff>()
        val pendingQueue = koin.get<PendingQueue>()
        val cooldown = koin.get<Cooldown>()

        runBlocking {
            // The scan shares the pool's backoff policy; see RateLimitBackoff.
            // TODO: make this a scanner class
            val fetches = performInitialScan(
                accountsClient, account, config.initialScanSize,
                rateLimitBackoff = rateLimitBackoff,
                transientMaxRetry = config.transientMaxRetry,
                transientRetryDelay = config.transientRetryDelay,
            )

            pendingQueue.feed(fetches)

            while (pendingQueue.hasNext()) {
                val fetched = pendingQueue.next()
                val toot = fetched.status

                // skip translation toots
                if (pendingQueue.isTranslation(toot.id)) {
                    pendingQueue.drop(toot.id)
                    continue
                }

                // check cooldown
                if (!cooldown.checkCooldown(fetched)) {
                    // skip and wait for the next round of scan
                    // but must ensure we have cursor ready
                    // if not, drop the current toot and make it the cursor
                    // TODO: the normal scan should require the cursor
                    //       and use it as sinceId, and all the way to head
                    if (pendingQueue.cursor() == null) {
                        logger.warn { "Dropping toot ${toot.id} as cursor is null" }
                        pendingQueue.drop(toot.id)
                    }
                    break
                }

                // check translation state
                when (val state = pendingQueue.findTranslationChainState(toot)) {
                    is TranslationChainState.Translated -> logger.info {
                        "Already translated: id=${toot.id}, segments=${state.chain.segments.size}"
                    }

                    is TranslationChainState.NeedsTranslation -> {
                        if (state.obsolete.isNotEmpty()) {
                            // leaf-first, as ordered by findTranslationChainState
                            deleter.delete(state.obsolete)
                            logger.info {
                                "Deleted obsolete segments: ${state.obsolete.joinToString { it.id }}"
                            }
                        }

                        // TODO: LLM translate and post. Currently use logging as placeholder
                        //       First build the reply chain at a given limit,
                        //       then search for valid translation chains for each of them with best efforts
                        logger.info {
                            "Pending translation: id=${toot.id}, " +
                                    "reason=${state.reason}, obsolete=${state.obsolete.size}, " +
                                    "cw=${toot.spoilerText}, content=${toot.content}"
                        }
                    }
                }

                // TODO: any error that should be retried should break the loop
                //       so the head is not dropped and will be re-processed on next loop

                // finished
                pendingQueue.drop(toot.id)
            }

            logger.info { "Loop finished, actual loop-and-scan not implemented yet" }
        }
    } finally {
        stopKoin()
    }
}
