package info.skyblond.fediverse.toot.trans.di

import info.skyblond.fediverse.toot.trans.TranslationProcessor
import info.skyblond.fediverse.toot.trans.config.ConfigProvider
import info.skyblond.fediverse.toot.trans.config.ConfigStore
import info.skyblond.fediverse.toot.trans.config.DefaultConfigProvider
import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import info.skyblond.fediverse.toot.trans.llm.Translator
import info.skyblond.fediverse.toot.trans.llm.TranslationRequestFactory
import info.skyblond.fediverse.toot.trans.llm.client.ChatApiException
import info.skyblond.fediverse.toot.trans.llm.client.ChatClient
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionRequest
import info.skyblond.fediverse.toot.trans.llm.client.ChatMessage
import info.skyblond.fediverse.toot.trans.llm.client.ReasoningEffort
import info.skyblond.fediverse.toot.trans.llm.client.RetryingChatClient
import info.skyblond.fediverse.toot.trans.llm.client.firstChoiceText
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.accounts.OkHttpAccountsClient
import info.skyblond.fediverse.toot.trans.masto.client.media.MediaLoader
import info.skyblond.fediverse.toot.trans.masto.client.statuses.OkHttpStatusesClient
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import info.skyblond.fediverse.toot.trans.masto.statuses.ReplyChainContext
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesDeleter
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.io.TempDir
import org.koin.core.KoinApplication
import org.koin.core.error.InstanceCreationException
import org.koin.dsl.koinApplication
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AppModuleTest {

    // valid stand-ins for the required config: resolving anything that
    // depends on the API transport reads (and parses) these first
    private val testHost = "https://instance.example/"
    private val testToken = "token"

    /**
     * A [ConfigStore] over a single provider with the given values,
     * falling back to [DefaultConfigProvider] like the real chain does.
     * */
    private fun configWith(
        mastoHost: String? = null,
        accessToken: String? = null,
        poolBatchWindow: Duration? = null,
        poolCacheTtl: Duration? = null,
        poolMaxCacheEntries: Int? = null,
        backoffResetMargin: Duration? = null,
        backoffFallbackDelay: Duration? = null,
        backoffMaxWaitDuration: Duration? = null,
        initialScanSize: Int? = null,
        transientMaxRetry: Int? = null,
        transientRetryDelay: Duration? = null,
        targetLanguage: String? = null,
        targetLangCode: String? = null,
        translationCooldown: Duration? = null,
        translationMaxAge: Duration? = null,
        replyChainDepth: Int? = null,
        llmBaseUrl: String? = null,
        llmApiKey: String? = null,
        llmConnectTimeout: Duration? = null,
        llmReadTimeout: Duration? = null,
        llmWriteTimeout: Duration? = null,
        llmModel: String? = null,
        llmMaxCompletionTokens: Int? = null,
        llmReasoningEffort: ReasoningEffort? = null,
        llmReqExtra: JsonObject? = null,
    ): ConfigStore = ConfigStore(
        listOf(
            object : ConfigProvider {
                override val mastoHost: String? = mastoHost
                override val accessToken: String? = accessToken
                override val poolBatchWindow: Duration? = poolBatchWindow
                override val poolCacheTtl: Duration? = poolCacheTtl
                override val poolMaxCacheEntries: Int? = poolMaxCacheEntries
                override val backoffResetMargin: Duration? = backoffResetMargin
                override val backoffFallbackDelay: Duration? = backoffFallbackDelay
                override val backoffMaxWaitDuration: Duration? = backoffMaxWaitDuration
                override val initialScanSize: Int? = initialScanSize
                override val transientMaxRetry: Int? = transientMaxRetry
                override val transientRetryDelay: Duration? = transientRetryDelay
                override val targetLanguage: String? = targetLanguage
                override val targetLangCode: String? = targetLangCode
                override val translationCooldown: Duration? = translationCooldown
                override val translationMaxAge: Duration? = translationMaxAge
                override val replyChainDepth: Int? = replyChainDepth
                override val llmBaseUrl: String? = llmBaseUrl
                override val llmApiKey: String? = llmApiKey
                override val llmConnectTimeout: Duration? = llmConnectTimeout
                override val llmReadTimeout: Duration? = llmReadTimeout
                override val llmWriteTimeout: Duration? = llmWriteTimeout
                override val llmModel: String? = llmModel
                override val llmMaxCompletionTokens: Int? = llmMaxCompletionTokens
                override val llmReasoningEffort: ReasoningEffort? = llmReasoningEffort
                override val llmReqExtra: JsonObject? = llmReqExtra
            },
            DefaultConfigProvider(),
        )
    )

    /**
     * Start a standalone container on the given config; a fresh module
     * per call, so the definitions close over exactly that config. The
     * environment defaults to empty, so the host's proxy variables
     * (`HTTPS_PROXY` and friends) cannot route the stand-in instance
     * connections through a proxy; the proxy tests pass their own.
     * */
    private fun appWith(
        config: ConfigStore,
        env: (String) -> String? = emptyMap<String, String>()::get,
    ): KoinApplication =
        koinApplication { modules(appModule(config, env)) }

    /**
     * Run [block] and return the failure it throws, unwrapping Koin's
     * [InstanceCreationException] wrappers down to the root cause: a
     * definition lambda that throws (a fail-fast config validation)
     * surfaces through the container possibly nested once per graph
     * level, with the original exception as the deepest cause.
     * */
    private fun assertFailsFast(block: () -> Unit): Throwable {
        var error = assertFails { block() }
        while (error is InstanceCreationException && error.cause != null) {
            error = error.cause!!
        }
        return error
    }

    @Test
    fun `resolves the whole graph with a complete config`() {
        val app = appWith(
            configWith(
                mastoHost = testHost, accessToken = testToken, targetLangCode = "en",
                llmBaseUrl = "https://llm.example/v1", llmApiKey = "llm-key",
            )
        )
        try {
            // interface definitions point at the OkHttp implementations
            assertIs<OkHttpAccountsClient>(app.koin.get<AccountsClient>())
            assertIs<OkHttpStatusesClient>(app.koin.get<StatusesClient>())

            // The container owns the worker and waits for it during teardown.
            app.koin.get<StatusesCachePool>()

            // the shared backoff single: resolved by the pool above and,
            // like Main.kt does for the boot-up scan, directly too
            app.koin.get<RateLimitBackoff>()
            // the cooldown policy: construction performs no IO, so a
            // complete config must resolve it too (the wrapper type is
            // pinned by the dedicated test below)
            app.koin.get<Cooldown>()
            // its construction performs no IO either, so a complete
            // config must resolve it too (the wrapper type is pinned
            // by the dedicated test below)
            app.koin.get<ChatClient>()

            // CredentialAccount is left out here: its resolution performs
            // network IO, so it gets the dedicated MockWebServer-backed
            // tests below instead of the fake host above; StatusesDeleter
            // and PendingQueue depend on the resolved account and are
            // covered there too
        } finally {
            app.close()
        }
    }

    /**
     * A local twin of MastoApiTestBase's enqueueJson, kept private so
     * this DI test stands alone instead of inheriting the API client
     * scaffolding it does not otherwise need.
     * */
    private fun MockWebServer.enqueueJson(code: Int, body: String) {
        enqueue(
            MockResponse.Builder()
                .code(code)
                .body(body)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .build()
        )
    }

    /**
     * [CredentialAccount] is the one graph node that does network IO
     * at resolution: the stand-in instance answers the call, and the
     * request count pins the single semantics — fetched exactly once,
     * then served from the cache. [PendingQueue] and [StatusesDeleter]
     * depend on the resolved account and must reuse it without another
     * call.
     * */
    @Test
    fun `resolves the credential account from the instance once`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
            val app = appWith(
                configWith(
                    mastoHost = server.url("/").toString(), accessToken = testToken, targetLangCode = "en",
                )
            )
            try {
                val account = app.koin.get<CredentialAccount>()
                assertEquals("14715", account.id)
                assertEquals("trwnh", account.username)

                // the second resolution serves the cached single
                // instead of calling the instance again
                assertEquals(account, app.koin.get<CredentialAccount>())
                // PendingQueue resolves on top of that cached account...
                assertIs<PendingQueue>(app.koin.get<PendingQueue>())
                // ...and StatusesDeleter shares the client, pool, backoff
                // and queue singles with the pool built above
                assertIs<StatusesDeleter>(app.koin.get<StatusesDeleter>())
                assertEquals(1, server.requestCount)
            } finally {
                app.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * A rejected token fails the resolution itself (fail-fast at the
     * container), with the server's error as the root cause under
     * Koin's wrapping.
     * */
    @Test
    fun `rejected credentials fail account resolution`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(401, """{"error": "The access token is invalid"}""")
            val app = appWith(configWith(mastoHost = server.url("/").toString(), accessToken = testToken))
            try {
                val error = assertIs<MastoApiException>(
                    assertFailsFast { app.koin.get<CredentialAccount>() }
                )
                assertEquals(401, error.httpCode)
                assertEquals("The access token is invalid", error.detail)
            } finally {
                app.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * Pins the onClose wiring in [appModule]: closing the container
     * cancels the application scope and closes the pool. Dropping an
     * onClose callback would leave resolution working; only this test
     * would notice.
     * */
    @Test
    fun `closing the container cancels the application scope and closes the pool`() {
        val app = appWith(configWith(mastoHost = testHost, accessToken = testToken))
        try {
            val scope = app.koin.get<CoroutineScope>()
            val pool = app.koin.get<StatusesCachePool>()
            app.close()
            assertFalse(scope.isActive)
            assertTrue(scope.coroutineContext[Job]!!.children.none(), "Container close must join the pool worker")
            // a closed pool rejects further lookups: getStatus fails on
            // the cancelled worker instead of queueing the request
            val error = assertFails { runBlocking { pool.getStatus("1") } }
            assertIs<CancellationException>(error)
        } finally {
            // no-op if the close above already ran
            app.close()
        }
    }

    @Test
    fun `missing required config fails client resolution`() {
        // nothing configured: required host and token are missing
        val app = appWith(configWith())
        try {
            val error = assertFailsFast { app.koin.get<AccountsClient>() }
            assertIs<MissingConfigException>(error)
        } finally {
            app.close()
        }
    }

    @Test
    fun `invalid host config fails client resolution`() {
        // the token must be present: the definition reads it too, and
        // the token read happens before the client parses the host
        val app = appWith(configWith(mastoHost = "not a url", accessToken = testToken))
        try {
            val error = assertFailsFast { app.koin.get<AccountsClient>() }
            assertIs<IllegalArgumentException>(error)
        } finally {
            app.close()
        }
    }

    @Test
    fun `invalid pool config fails pool resolution`() {
        // each value must actually reach the pool: the pool's constructor
        // rejects it, and the error names the offending setting. A
        // dropped argument in the module would silently fall back to
        // the pool's own default and nothing would fail.
        // The required host and token must be present too: resolving the
        // pool first resolves its client, whose construction reads them.
        val cases = mapOf(
            "batchWindow" to configWith(mastoHost = testHost, accessToken = testToken, poolBatchWindow = Duration.ZERO),
            "cacheTtl" to configWith(mastoHost = testHost, accessToken = testToken, poolCacheTtl = Duration.ZERO),
            "maxCacheEntries" to configWith(mastoHost = testHost, accessToken = testToken, poolMaxCacheEntries = 0),
        )
        cases.forEach { (setting, config) ->
            val app = appWith(config)
            try {
                val error = assertFailsFast { app.koin.get<StatusesCachePool>() }
                assertIs<IllegalArgumentException>(error)
                assertTrue(error.message!!.contains(setting), "error must name $setting: ${error.message}")
            } finally {
                app.close()
            }
        }
    }

    /**
     * Same fail-fast contract as the pool test above, but for the
     * dedicated backoff single: resolving it directly — without the
     * pool's help — rejects a bad value, proving the backoff* values
     * are wired into that definition.
     * */
    @Test
    fun `invalid backoff config fails backoff resolution`() {
        val cases = mapOf(
            "resetMargin" to configWith(backoffResetMargin = (-1).seconds),
            "fallbackDelay" to configWith(backoffFallbackDelay = Duration.ZERO),
            "maxWaitDuration" to configWith(backoffMaxWaitDuration = Duration.ZERO),
        )
        cases.forEach { (setting, config) ->
            val app = appWith(config)
            try {
                val error = assertFailsFast { app.koin.get<RateLimitBackoff>() }
                assertIs<IllegalArgumentException>(error)
                assertTrue(error.message!!.contains(setting), "error must name $setting: ${error.message}")
            } finally {
                app.close()
            }
        }
    }

    @Test
    fun `config chain keeps provider precedence`(@TempDir tempDir: File) {
        val file = tempDir.resolve("config.properties")
        file.writeText(
            "mastoHost=https://file.example/\n" +
                    "accessToken=fileToken\n" +
                    "targetLanguage=English\ntargetLangCode=en\n"
        )

        val store = appConfigStore(
            env = mapOf(
                "MASTO_HOST" to "https://env.example/",
                "INITIAL_SCAN_SIZE" to "5",
                "TARGET_LANG_CODE" to "ja",
                "TRANSLATION_COOLDOWN" to "0s",
            )::get,
            propertiesPath = file.absolutePath,
        )

        // environment variables beat the properties file
        assertEquals("https://env.example/", store.mastoHost)
        assertEquals(5, store.initialScanSize)
        assertEquals("ja", store.targetLangCode)
        assertEquals(Duration.ZERO, store.translationCooldown)
        // the properties file beats the built-in defaults
        assertEquals("fileToken", store.accessToken)
        assertEquals("English", store.targetLanguage)
        // the defaults fill what nobody configured
        assertEquals(StatusesCachePool.DEFAULT_BATCH_WINDOW, store.poolBatchWindow)
    }

    @Test
    fun `missing target lang config fails appConfigStore before boot`(@TempDir tempDir: File) {
        // host and token are configured, but the required target lang
        // configs are not: the sanity log in [appConfigStore] resolves
        // them, so the boot fails instead of running with a half-empty
        // config
        val file = tempDir.resolve("config.properties")
        file.writeText("mastoHost=https://file.example/\naccessToken=fileToken\n")

        val e = assertFailsWith<MissingConfigException> {
            appConfigStore(
                env = emptyMap<String, String>()::get,
                propertiesPath = file.absolutePath,
            )
        }
        assertTrue(e.message!!.contains("target language"))
    }

    /**
     * The cooldown single validates its config through the predicate
     * shared with [Cooldown]'s own constructor check, so the error names
     * `translationCooldown` instead of the component's parameter name.
     * Zero is a legal cooldown (it disables the wait), so it must
     * resolve.
     * */
    @Test
    fun `invalid cooldown fails cooldown resolution and zero is accepted`() {
        for (cooldown in listOf((-1).seconds, Duration.INFINITE)) {
            val app = appWith(configWith(translationCooldown = cooldown))
            try {
                val error = assertFailsFast { app.koin.get<Cooldown>() }
                assertIs<IllegalArgumentException>(error)
                assertTrue(error.message!!.contains("translationCooldown"))
            } finally {
                app.close()
            }
        }
        val app = appWith(configWith(translationCooldown = Duration.ZERO))
        try {
            assertIs<Cooldown>(app.koin.get<Cooldown>())
        } finally {
            app.close()
        }
    }

    /**
     * The max-age cutoff is validated at the queue definition through the
     * predicate shared with `PendingQueue`'s constructor, so a bad value
     * fails naming `translationMaxAge` instead of the component's
     * parameter name. Resolving the queue resolves the account first,
     * hence the stand-in instance.
     * */
    @Test
    fun `invalid translation max age fails queue resolution naming the setting`() {
        val server = MockWebServer()
        server.start()
        try {
            for (bad in listOf((-1).seconds, Duration.INFINITE)) {
                server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
                val app = appWith(
                    configWith(
                        mastoHost = server.url("/").toString(), accessToken = testToken,
                        targetLangCode = "en", translationMaxAge = bad,
                    )
                )
                try {
                    val error = assertIs<IllegalArgumentException>(assertFailsFast { app.koin.get<PendingQueue>() })
                    assertTrue(
                        error.message!!.contains("translationMaxAge"),
                        "error must name translationMaxAge: ${error.message}",
                    )
                } finally {
                    app.close()
                }
            }
        } finally {
            server.close()
        }
    }

    /**
     * The accepting end of the cooldown/cutoff consistency check: the
     * check only applies when both bounds are enabled, so any pair with
     * `0s` on either side must resolve, and so must a valid strict pair.
     * */
    @Test
    fun `disabled cooldown or max age bounds resolve the queue`() {
        val server = MockWebServer()
        server.start()
        try {
            val accepted = listOf(
                Duration.ZERO to Duration.ZERO,
                Duration.ZERO to 1.days,
                1.days to Duration.ZERO,
                1.minutes to 1.days,
            )
            for ((cooldown, maxAge) in accepted) {
                server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
                val app = appWith(
                    configWith(
                        mastoHost = server.url("/").toString(), accessToken = testToken,
                        targetLangCode = "en", translationCooldown = cooldown, translationMaxAge = maxAge,
                    )
                )
                try {
                    assertIs<PendingQueue>(app.koin.get<PendingQueue>())
                } finally {
                    app.close()
                }
            }
        } finally {
            server.close()
        }
    }

    /**
     * The rejecting end: with both bounds enabled, a cooldown at or
     * above the cutoff leaves no eligible age window — every toot is
     * blocked by the cooldown until the cutoff rejects it — so the
     * queue definition fails naming both settings.
     * */
    @Test
    fun `a cooldown at or above the cutoff fails queue resolution naming both settings`() {
        val server = MockWebServer()
        server.start()
        try {
            for ((cooldown, maxAge) in listOf(1.days to 1.days, 2.days to 1.days)) {
                server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
                val app = appWith(
                    configWith(
                        mastoHost = server.url("/").toString(), accessToken = testToken,
                        targetLangCode = "en", translationCooldown = cooldown, translationMaxAge = maxAge,
                    )
                )
                try {
                    val error = assertIs<IllegalArgumentException>(assertFailsFast { app.koin.get<PendingQueue>() })
                    assertTrue(error.message!!.contains("translationMaxAge"))
                    assertTrue(error.message!!.contains("translationCooldown"))
                } finally {
                    app.close()
                }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `resolves the chat client as the retrying transport`() {
        val app = appWith(
            configWith(
                mastoHost = testHost, accessToken = testToken,
                llmBaseUrl = "https://llm.example/v1", llmApiKey = "llm-key",
            )
        )
        try {
            assertIs<RetryingChatClient>(app.koin.get<ChatClient>())
        } finally {
            app.close()
        }
    }

    @Test
    fun `missing LLM config fails chat client resolution`() {
        // the base URL and key are read while the client is built; neither
        // is configured here
        val app = appWith(configWith(mastoHost = testHost, accessToken = testToken))
        try {
            val error = assertFailsFast { app.koin.get<ChatClient>() }
            assertIs<MissingConfigException>(error)
            assertTrue(error.message!!.contains("llm base url"))
        } finally {
            app.close()
        }
    }

    /**
     * A typo'd base URL must fail naming the setting, not with the
     * transport's own "Base URL" wording; the endpoint-shape cases
     * (query, fragment, endpoint duplication) stay with the transport,
     * see `OkHttpChatClient`'s KDoc.
     * */
    @Test
    fun `malformed llm base url fails chat client resolution naming the setting`() {
        val app = appWith(
            configWith(
                mastoHost = testHost, accessToken = testToken,
                llmBaseUrl = "not a url", llmApiKey = "llm-key",
            )
        )
        try {
            val error = assertIs<IllegalArgumentException>(assertFailsFast { app.koin.get<ChatClient>() })
            assertTrue(error.message!!.contains("llmBaseUrl"), "error must name llmBaseUrl: ${error.message}")
        } finally {
            app.close()
        }
    }

    /**
     * One case per timeout setting, for both rejected value kinds
     * (negative and infinite): the shared client would resolve with
     * OkHttp's defaults, so dropping an argument from the module would
     * leave its case resolving instead of failing. The error must name
     * the setting, not OkHttp's own parameter-named failure.
     * */
    @Test
    fun `negative and infinite llm timeouts fail chat client resolution`() {
        for (bad in listOf((-1).seconds, Duration.INFINITE)) {
            val cases = mapOf(
                "llmConnectTimeout" to configWith(
                    mastoHost = testHost, accessToken = testToken, llmBaseUrl = "https://llm.example/v1",
                    llmApiKey = "llm-key", llmConnectTimeout = bad,
                ),
                "llmReadTimeout" to configWith(
                    mastoHost = testHost, accessToken = testToken, llmBaseUrl = "https://llm.example/v1",
                    llmApiKey = "llm-key", llmReadTimeout = bad,
                ),
                "llmWriteTimeout" to configWith(
                    mastoHost = testHost, accessToken = testToken, llmBaseUrl = "https://llm.example/v1",
                    llmApiKey = "llm-key", llmWriteTimeout = bad,
                ),
            )
            for ((setting, config) in cases) {
                val app = appWith(config)
                try {
                    val error = assertIs<IllegalArgumentException>(assertFailsFast { app.koin.get<ChatClient>() })
                    assertTrue(error.message!!.contains(setting), "error must name $setting: ${error.message}")
                } finally {
                    app.close()
                }
            }
        }
    }

    /**
     * The accepting end of the timeout validation: zero is a legal
     * timeout (OkHttp's "no timeout", see the README's `0s` disables),
     * so a config with all three set to zero must resolve.
     * */
    @Test
    fun `zero llm timeouts are accepted and disable the timeout`() {
        val app = appWith(
            configWith(
                mastoHost = testHost, accessToken = testToken,
                llmBaseUrl = "https://llm.example/v1", llmApiKey = "llm-key",
                llmConnectTimeout = Duration.ZERO,
                llmReadTimeout = Duration.ZERO,
                llmWriteTimeout = Duration.ZERO,
            )
        )
        try {
            assertIs<RetryingChatClient>(app.koin.get<ChatClient>())
        } finally {
            app.close()
        }
    }

    /**
     * The shared transient* budget is validated in the module before it
     * reaches the retry wrapper, whose own constructor errors name its
     * parameters instead of the config setting — dropping the module
     * validation would swap in that worse message. The `maxRetry + 1`
     * attempt-budget conversion also means Int.MAX_VALUE must be
     * rejected: the overflow would surface as a negative maxAttempts.
     * */
    @Test
    fun `invalid transient retry config fails chat client resolution naming the setting`() {
        val cases = listOf(
            "transientMaxRetry" to configWith(
                mastoHost = testHost, accessToken = testToken, llmBaseUrl = "https://llm.example/v1",
                llmApiKey = "llm-key", transientMaxRetry = -1,
            ),
            "transientMaxRetry" to configWith(
                mastoHost = testHost, accessToken = testToken, llmBaseUrl = "https://llm.example/v1",
                llmApiKey = "llm-key", transientMaxRetry = Int.MAX_VALUE,
            ),
            "transientRetryDelay" to configWith(
                mastoHost = testHost, accessToken = testToken, llmBaseUrl = "https://llm.example/v1",
                llmApiKey = "llm-key", transientRetryDelay = Duration.ZERO,
            ),
        )
        for ((setting, config) in cases) {
            val app = appWith(config)
            try {
                val error = assertIs<IllegalArgumentException>(assertFailsFast { app.koin.get<ChatClient>() })
                assertTrue(error.message!!.contains(setting), "error must name $setting: ${error.message}")
            } finally {
                app.close()
            }
        }
    }

    /**
     * End-to-end wiring: the config's base URL and key reach the wire
     * (endpoint appended to the base path, bearer auth), and the global
     * transient config drives the retry budget — one retry is spent on
     * the 500, then the second attempt succeeds. A dropped or off-by-one
     * attempt budget would fail this test.
     * */
    @Test
    fun `chat client posts through the derived endpoint and retries per the transient config`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(500, """{"error": "overloaded"}""")
            server.enqueueJson(
                200,
                """{"choices": [{"message": {"role": "assistant", "content": "Hello"}, "finish_reason": "stop"}]}"""
            )
            val app = appWith(
                configWith(
                    mastoHost = testHost, accessToken = testToken,
                    transientMaxRetry = 1, transientRetryDelay = 1.milliseconds,
                    llmBaseUrl = server.url("/v1").toString(), llmApiKey = "llm-key",
                )
            )
            try {
                val response = runBlocking {
                    app.koin.get<ChatClient>().complete(
                        ChatCompletionRequest(model = "test-model", messages = listOf(ChatMessage.user("hi")))
                    )
                }

                assertEquals("Hello", response.firstChoiceText())
                assertEquals(2, server.requestCount)
                repeat(2) {
                    val recorded = server.takeRequest()
                    assertEquals("POST", recorded.method)
                    assertEquals("/v1/chat/completions", recorded.target)
                    assertEquals("Bearer llm-key", recorded.headers["Authorization"])
                }
            } finally {
                app.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * The other end of the attempt-budget mapping: a zero transient
     * retry budget means one attempt, so the 500 propagates untouched.
     * */
    @Test
    fun `zero transient retry budget disables chat retries`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(500, """{"error": "overloaded"}""")
            val app = appWith(
                configWith(
                    mastoHost = testHost, accessToken = testToken,
                    transientMaxRetry = 0, transientRetryDelay = 1.milliseconds,
                    llmBaseUrl = server.url("/v1").toString(), llmApiKey = "llm-key",
                )
            )
            try {
                val error = assertFailsWith<ChatApiException> {
                    runBlocking {
                        app.koin.get<ChatClient>().complete(
                            ChatCompletionRequest(model = "test-model", messages = listOf(ChatMessage.user("hi")))
                        )
                    }
                }
                assertEquals(500, error.httpCode)
                assertEquals(1, server.requestCount)
            } finally {
                app.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * The translation stage resolves as one graph: media loader,
     * request factory, translator, context builder and the
     * queue-draining processor. The account-dependent nodes come from
     * the stand-in instance, like the [PendingQueue] test above.
     * */
    @Test
    fun `resolves the translation stage with a complete config`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
            val app = appWith(
                configWith(
                    mastoHost = server.url("/").toString(), accessToken = testToken,
                    targetLanguage = "English", targetLangCode = "en",
                    llmBaseUrl = "https://llm.example/v1", llmApiKey = "llm-key",
                    llmModel = "test-model", llmMaxCompletionTokens = 1024,
                )
            )
            try {
                assertIs<MediaLoader>(app.koin.get<MediaLoader>())
                assertIs<TranslationRequestFactory>(app.koin.get<TranslationRequestFactory>())
                assertIs<Translator>(app.koin.get<Translator>())
                assertIs<ReplyChainContext>(app.koin.get<ReplyChainContext>())
                assertIs<TranslationProcessor>(app.koin.get<TranslationProcessor>())
            } finally {
                app.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * The model name and token budget are only consumed by the
     * translation stage; the definition reads them, so a config
     * without them fails at resolution naming the setting instead of
     * at the first completion.
     * */
    @Test
    fun `missing llm model fails the translation stage naming the setting`() {
        val app = appWith(
            configWith(
                targetLanguage = "English", targetLangCode = "en",
                llmBaseUrl = "https://llm.example/v1", llmApiKey = "llm-key",
            )
        )
        try {
            val error = assertFailsFast { app.koin.get<TranslationRequestFactory>() }
            assertIs<MissingConfigException>(error)
            assertTrue(error.message!!.contains("llm model"))
        } finally {
            app.close()
        }
    }

    /**
     * The token budget is validated at the definition through the
     * predicate shared with the factory, so a non-positive value fails
     * naming `llmMaxCompletionTokens` instead of surfacing as a
     * per-toot request-construction failure at the first completion.
     * */
    @Test
    fun `invalid llm max completion tokens fails the translation stage naming the setting`() {
        val app = appWith(
            configWith(
                targetLanguage = "English", targetLangCode = "en",
                llmBaseUrl = "https://llm.example/v1", llmApiKey = "llm-key",
                llmModel = "test-model", llmMaxCompletionTokens = 0,
            )
        )
        try {
            val error = assertFailsFast { app.koin.get<TranslationRequestFactory>() }
            assertIs<IllegalArgumentException>(error)
            assertTrue(error.message!!.contains("llmMaxCompletionTokens"))
        } finally {
            app.close()
        }
    }

    /**
     * The depth validation runs at the definition through the
     * predicate shared with the component, so the error names
     * `replyChainDepth`; dropping it would surface the component's own
     * parameter-named error instead.
     * */
    @Test
    fun `invalid reply chain depth fails the context builder naming the setting`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
            val app = appWith(
                configWith(
                    mastoHost = server.url("/").toString(), accessToken = testToken,
                    targetLangCode = "en", replyChainDepth = -1,
                )
            )
            try {
                val error = assertFailsFast { app.koin.get<ReplyChainContext>() }
                assertIs<IllegalArgumentException>(error)
                assertTrue(error.message!!.contains("replyChainDepth"))
            } finally {
                app.close()
            }
        } finally {
            server.close()
        }
    }

    /**
     * The shared client's proxy comes from the conventional environment
     * variables (see [proxyFromEnv]): dropping the wiring would silently
     * send traffic outside the configured proxy, and nothing else would
     * fail.
     * */
    @Test
    fun `the shared client picks up the proxy from the environment`() {
        val app = appWith(
            configWith(mastoHost = testHost, accessToken = testToken),
            env = mapOf("HTTPS_PROXY" to "http://127.0.0.1:3128")::get,
        )
        try {
            val resolved = assertNotNull(app.koin.get<OkHttpClient>().proxy)
            assertEquals(Proxy.Type.HTTP, resolved.type())
            val address = assertIs<InetSocketAddress>(resolved.address())
            assertEquals("127.0.0.1", address.hostString)
            assertEquals(3128, address.port)
        } finally {
            app.close()
        }
    }

    /**
     * The other end: with no proxy variable the client connects
     * directly. The empty environment is [appWith]'s default, which
     * also keeps this machine's proxy variables out of every test.
     * */
    @Test
    fun `the shared client connects directly without proxy variables`() {
        val app = appWith(configWith(mastoHost = testHost, accessToken = testToken))
        try {
            assertNull(app.koin.get<OkHttpClient>().proxy)
        } finally {
            app.close()
        }
    }
}
