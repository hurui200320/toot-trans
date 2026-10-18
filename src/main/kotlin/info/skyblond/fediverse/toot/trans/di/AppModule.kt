package info.skyblond.fediverse.toot.trans.di

import info.skyblond.fediverse.toot.trans.TranslationProcessor
import info.skyblond.fediverse.toot.trans.config.ConfigStore
import info.skyblond.fediverse.toot.trans.config.DefaultConfigProvider
import info.skyblond.fediverse.toot.trans.config.EnvConfigProvider
import info.skyblond.fediverse.toot.trans.config.file.PropertiesConfigProvider
import info.skyblond.fediverse.toot.trans.config.requireValidCooldown
import info.skyblond.fediverse.toot.trans.config.requireValidMaxCompletionTokens
import info.skyblond.fediverse.toot.trans.config.requireValidReplyChainDepth
import info.skyblond.fediverse.toot.trans.config.requireValidRetryDelay
import info.skyblond.fediverse.toot.trans.config.requireValidTransientMaxRetry
import info.skyblond.fediverse.toot.trans.llm.TranslationRequestFactory
import info.skyblond.fediverse.toot.trans.llm.Translator
import info.skyblond.fediverse.toot.trans.llm.client.ChatClient
import info.skyblond.fediverse.toot.trans.llm.client.OkHttpChatClient
import info.skyblond.fediverse.toot.trans.llm.client.RetryingChatClient
import info.skyblond.fediverse.toot.trans.logger
import info.skyblond.fediverse.toot.trans.masto.MastoApiClient
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.accounts.OkHttpAccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.media.MediaLoader
import info.skyblond.fediverse.toot.trans.masto.client.media.OkHttpMediaLoader
import info.skyblond.fediverse.toot.trans.masto.client.statuses.OkHttpStatusesClient
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import info.skyblond.fediverse.toot.trans.masto.statuses.ReplyChainContext
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesDeleter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.koin.core.module.Module
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module
import java.io.File
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * The Koin container's module: the whole application object graph.
 *
 * The module is parameterized by the resolved [ConfigStore] (see
 * [appConfigStore]) and by the environment lookup [env] used for the
 * conventional proxy variables (see [proxyFromEnv]; injectable so
 * tests can keep the host's proxy setup out of their containers):
 * every definition constructs its object directly, pulling container
 * dependencies with `get()` and plain values from [config], so a
 * missing or invalid value fails the resolution of the dependents
 * (fail-fast) instead of producing a half-configured object.
 *
 * The one exception is [CredentialAccount]: it is fetched from the
 * instance at first resolution, so a rejected access token fails the
 * resolution itself, not just the first API consumer — see that
 * definition for the blocking resolution contract.
 * */
fun appModule(
    config: ConfigStore,
    env: (String) -> String? = System::getenv,
): Module = module {
    // koin's module DSL has its own `logger`; the FQN calls below use the
    // application logger instead
    // one client shared by every API component, so its connection pool
    // and threads are not duplicated per definition; the proxy follows
    // the conventional environment variables (see [proxyFromEnv]), and
    // the LLM transport, which derives from this client with newBuilder,
    // inherits it together with the pool
    single<OkHttpClient> {
        val httpProxy = proxyFromEnv(env)
        if (httpProxy == null) {
            info.skyblond.fediverse.toot.trans.logger.info { "No proxy configured, connecting directly" }
        } else {
            info.skyblond.fediverse.toot.trans.logger.info { "Using proxy: $httpProxy" }
        }
        OkHttpClient.Builder()
            .apply { httpProxy?.let { proxy(it) } }
            .build()
    }


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

    // the 429 backoff policy shared by the pool's worker and the
    // boot-up scan; the backoff* settings and their semantics are
    // documented on [RateLimitBackoff]
    single<RateLimitBackoff> {
        RateLimitBackoff(
            resetMargin = config.backoffResetMargin,
            fallbackDelay = config.backoffFallbackDelay,
            maxWaitDuration = config.backoffMaxWaitDuration,
        )
    }

    // the pool* settings and their semantics are documented on
    // [StatusesCachePool]; like the scope above, the pool is closed by
    // its onClose callback when the container stops; see [StatusesCachePool.close]
    // for the blocking teardown contract.
    single<StatusesCachePool> {
        StatusesCachePool(
            client = get(),
            scope = get(),
            batchWindow = config.poolBatchWindow,
            cacheTtl = config.poolCacheTtl,
            maxCacheEntries = config.poolMaxCacheEntries,
            rateLimitBackoff = get(),
        )
    } withOptions { onClose { it?.close() } }

    // the boot scan's work window: the account id comes from the resolved
    // [CredentialAccount] (its own definition performs the network IO), the
    // target language from config; the queue semantics live on
    // [PendingQueue]
    single<PendingQueue> {
        val account: CredentialAccount = get()
        PendingQueue(account.id, config.targetLangCode, get())
    }

    // the translation cooldown policy; the config value is validated here
    // through the predicate shared with [Cooldown]'s own constructor check
    // (see `config/Validation.kt`), so a bad setting fails naming
    // `translationCooldown` instead of the component's parameter name
    single<Cooldown> {
        Cooldown(requireValidCooldown("Config `translationCooldown`", config.translationCooldown))
    }

    // deletes obsolete translation segments through the same client,
    // dropping each deleted status from the pool and the pending queue;
    // the shared backoff singleton paces its 429 retries like the pool
    // worker and scan
    single<StatusesDeleter> {
        StatusesDeleter(get(), get(), get())
    }

    // downloads the current toot's images for the vision input; media
    // URLs are served publicly, so the shared client is used without
    // auth, and the caps live on the interface
    single<MediaLoader> {
        OkHttpMediaLoader(get())
    }

    // builds the translation request variants; the llm* settings are
    // read here, so the stage that consumes them fails fast on a
    // missing model or an invalid token budget, naming the setting
    single<TranslationRequestFactory> {
        TranslationRequestFactory(
            targetLanguage = config.targetLanguage,
            model = config.llmModel,
            reasoningEffort = config.llmReasoningEffort,
            maxCompletionTokens = requireValidMaxCompletionTokens(
                "Config `llmMaxCompletionTokens`",
                config.llmMaxCompletionTokens,
            ),
            extraParameter = config.llmReqExtra,
        )
    }

    // walks the request ladder over the retrying chat client
    single<Translator> {
        Translator(get(), get())
    }

    // the reply-chain context builder; the depth config is validated
    // here through the predicate shared with the component's own
    // constructor check, naming `replyChainDepth`
    single<ReplyChainContext> {
        ReplyChainContext(
            queue = get(),
            pool = get(),
            statusesClient = get(),
            mediaLoader = get(),
            maxDepth = requireValidReplyChainDepth("Config `replyChainDepth`", config.replyChainDepth),
        )
    }

    // the queue drain: context -> translate -> log, with the failure
    // policy documented on TranslationProcessor
    single<TranslationProcessor> {
        TranslationProcessor(
            queue = get(),
            cooldown = get(),
            deleter = get(),
            contextBuilder = get(),
            translator = get(),
        )
    }

    // the LLM chat client: the OkHttp transport (which owns no client,
    // see [OkHttpChatClient]) wrapped in the transient-retry policy, so
    // the graph's ChatClient is always the retrying one. The transport's
    // OkHttp derives from the shared client via newBuilder: connection
    // pool and threads stay shared, while the llm* timeouts apply to
    // LLM traffic only — the instance API keeps OkHttp's defaults.
    // Retries use the global transient* settings; maxAttempts counts the
    // initial attempt while the config counts retries after it (0
    // disables retries), hence the +1, see [RetryingChatClient].
    single<ChatClient> {
        val connectTimeout = config.llmConnectTimeout.checkHttpTimeout("llmConnectTimeout")
        val readTimeout = config.llmReadTimeout.checkHttpTimeout("llmReadTimeout")
        val writeTimeout = config.llmWriteTimeout.checkHttpTimeout("llmWriteTimeout")
        // the transport owns the endpoint-shape checks (query, fragment,
        // endpoint duplication, see [OkHttpChatClient]); this pre-check
        // only makes the common typo name the setting instead of the
        // transport's own "Base URL" wording
        val baseUrl = config.llmBaseUrl
        require(baseUrl.toHttpUrlOrNull() != null) {
            "Config `llmBaseUrl` is not a valid URL, got `$baseUrl`"
        }
        // the transient validation runs through the predicates shared
        // with the boot-up scan (see `config/Validation.kt`), naming the
        // setting here where the components would only name their
        // parameters
        val maxRetry = requireValidTransientMaxRetry("Config `transientMaxRetry`", config.transientMaxRetry)
        // the upper bound keeps the `maxRetry + 1` attempt-budget
        // conversion below from overflowing, which would reach
        // RetryingChatClient as a negative maxAttempts instead
        require(maxRetry < Int.MAX_VALUE) {
            "Config `transientMaxRetry` must be below ${Int.MAX_VALUE}, got $maxRetry"
        }
        val retryDelay = requireValidRetryDelay("Config `transientRetryDelay`", config.transientRetryDelay)
        RetryingChatClient(
            delegate = OkHttpChatClient(
                baseUrl = baseUrl,
                apiKey = config.llmApiKey,
                httpClient = get<OkHttpClient>().newBuilder()
                    .connectTimeout(connectTimeout.toJavaDuration())
                    .readTimeout(readTimeout.toJavaDuration())
                    .writeTimeout(writeTimeout.toJavaDuration())
                    .build(),
            ),
            maxAttempts = maxRetry + 1,
            retryDelay = retryDelay,
        )
    }

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
            info.skyblond.fediverse.toot.trans.logger.info {
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
    val lang = config.targetLanguage
    val langCode = config.targetLangCode
    logger.info { "Configured target language: $lang ($langCode)" }
}

/**
 * Validate a configured LLM HTTP timeout before it reaches OkHttp: fail-fast
 * naming the setting instead of surfacing OkHttp's generic "timeout < 0".
 * Zero is accepted — OkHttp treats it as "no timeout" — while an infinite
 * duration cannot be converted for OkHttp at all.
 * */
private fun Duration.checkHttpTimeout(name: String): Duration {
    require(isFinite() && !isNegative()) {
        "Config `$name` must be finite and non-negative, got $this"
    }
    return this
}
