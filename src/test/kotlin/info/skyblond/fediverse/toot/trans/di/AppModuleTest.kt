package info.skyblond.fediverse.toot.trans.di

import info.skyblond.fediverse.toot.trans.config.ConfigProvider
import info.skyblond.fediverse.toot.trans.config.ConfigStore
import info.skyblond.fediverse.toot.trans.config.DefaultConfigProvider
import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.accounts.AccountsClient
import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.accounts.OkHttpAccountsClient
import info.skyblond.fediverse.toot.trans.masto.statuses.OkHttpStatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.io.TempDir
import org.koin.core.KoinApplication
import org.koin.core.error.InstanceCreationException
import org.koin.dsl.koinApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
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
        poolResetMargin: Duration? = null,
        poolFallbackDelay: Duration? = null,
        poolMaxWaitDuration: Duration? = null,
    ): ConfigStore = ConfigStore(
        listOf(
            object : ConfigProvider {
                override val mastoHost: String? = mastoHost
                override val accessToken: String? = accessToken
                override val poolBatchWindow: Duration? = poolBatchWindow
                override val poolCacheTtl: Duration? = poolCacheTtl
                override val poolMaxCacheEntries: Int? = poolMaxCacheEntries
                override val poolResetMargin: Duration? = poolResetMargin
                override val poolFallbackDelay: Duration? = poolFallbackDelay
                override val poolMaxWaitDuration: Duration? = poolMaxWaitDuration
            },
            DefaultConfigProvider(),
        )
    )

    /**
     * Start a standalone container on the given config; a fresh module
     * per call, so the definitions close over exactly that config.
     * */
    private fun appWith(config: ConfigStore): KoinApplication =
        koinApplication { modules(appModule(config)) }

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
        val app = appWith(configWith(mastoHost = testHost, accessToken = testToken))
        try {
            // interface definitions point at the OkHttp implementations
            assertIs<OkHttpAccountsClient>(app.koin.get<AccountsClient>())
            assertIs<OkHttpStatusesClient>(app.koin.get<StatusesClient>())

            // The container owns the worker and waits for it during teardown.
            app.koin.get<StatusesPool>()

            // CredentialAccount is left out here: its resolution performs
            // network IO, so it gets the dedicated MockWebServer-backed
            // tests below instead of the fake host above
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
     * then served from the cache.
     * */
    @Test
    fun `resolves the credential account from the instance once`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueueJson(200, """{"id": "14715", "username": "trwnh"}""")
            val app = appWith(configWith(mastoHost = server.url("/").toString(), accessToken = testToken))
            try {
                val account = app.koin.get<CredentialAccount>()
                assertEquals("14715", account.id)
                assertEquals("trwnh", account.username)

                // the second resolution serves the cached single
                // instead of calling the instance again
                assertEquals(account, app.koin.get<CredentialAccount>())
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
            val pool = app.koin.get<StatusesPool>()
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
            "resetMargin" to configWith(mastoHost = testHost, accessToken = testToken, poolResetMargin = (-1).seconds),
            "fallbackDelay" to configWith(mastoHost = testHost, accessToken = testToken, poolFallbackDelay = Duration.ZERO),
            "maxWaitDuration" to configWith(mastoHost = testHost, accessToken = testToken, poolMaxWaitDuration = Duration.ZERO),
        )
        cases.forEach { (setting, config) ->
            val app = appWith(config)
            try {
                val error = assertFailsFast { app.koin.get<StatusesPool>() }
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
        file.writeText("mastoHost=https://file.example/\naccessToken=fileToken\n")

        val store = appConfigStore(
            env = mapOf("MASTO_HOST" to "https://env.example/")::get,
            propertiesPath = file.absolutePath,
        )

        // environment variables beat the properties file
        assertEquals("https://env.example/", store.mastoHost)
        // the properties file beats the built-in defaults
        assertEquals("fileToken", store.accessToken)
        // the defaults fill what nobody configured
        assertEquals(StatusesPool.DEFAULT_BATCH_WINDOW, store.poolBatchWindow)
    }
}
