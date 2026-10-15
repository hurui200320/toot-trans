package info.skyblond.fediverse.toot.trans.di

import info.skyblond.fediverse.toot.trans.config.ConfigStore
import info.skyblond.fediverse.toot.trans.config.DefaultConfigProvider
import info.skyblond.fediverse.toot.trans.config.EnvConfigProvider
import info.skyblond.fediverse.toot.trans.config.file.PropertiesConfigProvider
import info.skyblond.fediverse.toot.trans.masto.MastoApiClient
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.accounts.OkHttpAccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.OkHttpStatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module
import java.io.File

private val logger = KotlinLogging.logger("DI")

/**
 * The Koin container's module: the whole application object graph.
 *
 * The module is parameterized by the resolved [ConfigStore] (see
 * [appConfigStore]): every definition constructs its object directly,
 * pulling container dependencies with `get()` and plain values from
 * [config], so a missing or invalid value fails the resolution of the
 * dependents (fail-fast) instead of producing a half-configured object.
 *
 * The one exception is [CredentialAccount]: it is fetched from the
 * instance at first resolution, so a rejected access token fails the
 * resolution itself, not just the first API consumer — see that
 * definition for the blocking resolution contract.
 * */
fun appModule(config: ConfigStore): Module = module {
    // one client shared by every API component, so its connection pool
    // and threads are not duplicated per definition
    single<OkHttpClient> { OkHttpClient.Builder().build() }

    // shared transport for the instance API
    single<MastoApiClient> {
        MastoApiClient(config.mastoHost, config.accessToken, get())
    }

    single<AccountsClient> { OkHttpAccountsClient(get()) }
    single<StatusesClient> { OkHttpStatusesClient(get()) }

    // the pool's worker belongs to this application scope instead of
    // the main flow's runBlocking scope; the scope is cancelled by its
    // onClose callback when the container stops. SupervisorJob so a
    // failed child cannot cancel the scope, and Dispatchers.Default
    // because the worker only orchestrates — the blocking IO happens
    // inside OkHttp.
    single<CoroutineScope> {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    } withOptions { onClose { it?.cancel() } }

    // the pool* settings and their semantics are documented on
    // [StatusesPool]; like the scope above, the pool is closed by its
    // onClose callback when the container stops; see [StatusesPool.close]
    // for the blocking teardown contract.
    single<StatusesPool> {
        StatusesPool(
            client = get(),
            scope = get(),
            batchWindow = config.poolBatchWindow,
            cacheTtl = config.poolCacheTtl,
            maxCacheEntries = config.poolMaxCacheEntries,
            resetMargin = config.poolResetMargin,
            fallbackDelay = config.poolFallbackDelay,
            maxWaitDuration = config.poolMaxWaitDuration,
        )
    } withOptions { onClose { it?.close() } }

    // fetched from the instance with a blocking verify_credentials call
    // at first resolution: only resolve this bean from a thread that may
    // block on network IO — the main thread during startup, see Main.kt —
    // never from a coroutine on a shared dispatcher (e.g. the pool
    // worker's), which it would stall
    single<CredentialAccount> {
        val accountsClient = get<AccountsClient>()
        runBlocking(Dispatchers.IO) {
            accountsClient.verifyCredentials()
        }.also {
            info.skyblond.fediverse.toot.trans.di.logger.info {
                "Logged in as ${it.username}, user id: ${it.id}"
            }
        }
    }
}

/**
 * The application's [ConfigStore]: the config sources in precedence
 * order (environment variables, properties file, built-in defaults).
 * The resolution rules are documented on [ConfigStore]; the properties
 * path and its `CONFIG_PROPERTIES_PATH` override are user-facing
 * config, see the README.
 *
 * [env] and [propertiesPath] are injectable so tests can supply a fake
 * environment and file; production calls this with no arguments.
 * */
fun appConfigStore(
    env: (String) -> String? = System::getenv,
    propertiesPath: String = System.getenv("CONFIG_PROPERTIES_PATH") ?: "./config.properties",
): ConfigStore = ConfigStore(
    listOf(
        EnvConfigProvider(env),
        PropertiesConfigProvider(File(propertiesPath)),
        DefaultConfigProvider(),
    )
).also { config ->
    // logging also serves as sanity check
    config.mastoHost.let { logger.info { "Configured server host: $it" } }
}
