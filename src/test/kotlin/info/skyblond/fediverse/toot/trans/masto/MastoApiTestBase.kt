package info.skyblond.fediverse.toot.trans.masto

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * Scaffolding shared by the API client tests: a [MockWebServer]
 * standing in for the instance, with an [MastoApiClient] pointed at
 * it. Subclasses build their endpoint client on [api].
 * */
abstract class MastoApiTestBase {
    protected lateinit var server: MockWebServer
    protected lateinit var api: MastoApiClient

    @BeforeEach
    fun setUpApi() {
        server = MockWebServer()
        server.start()
        api = MastoApiClient(server.url("/").toString(), "test-token")
    }

    @AfterEach
    fun tearDownApi() {
        server.close()
    }

    /**
     * Enqueue [body] as a JSON response with status [code].
     * */
    protected fun enqueueJson(code: Int, body: String) {
        server.enqueue(
            MockResponse.Builder()
                .code(code)
                .body(body)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .build()
        )
    }
}
