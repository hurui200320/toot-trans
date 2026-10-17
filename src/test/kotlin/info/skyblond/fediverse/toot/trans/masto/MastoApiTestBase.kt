package info.skyblond.fediverse.toot.trans.masto

import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

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

    /**
     * A [Clock] whose every read returns a distinct instant (one second
     * past [base]) and records the server's request count at that
     * moment. A request cannot reach the server before the client sends
     * it, so a [requestCountAtStamp] that stays 0 across every read
     * proves they all preceded the request going out — the ordering the
     * cooldown proof relies on, see [FetchedStatus] — while the distinct
     * instants expose any per-entry stamping.
     * */
    protected class StampingClock(
        private val server: MockWebServer,
        private val base: Instant,
    ) : Clock() {
        var readings = 0
            private set

        /**
         * Highest request count observed while stamping: stays 0 exactly
         * while every clock read precedes the request.
         * */
        var requestCountAtStamp = 0
            private set

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)

        override fun instant(): Instant {
            requestCountAtStamp = maxOf(requestCountAtStamp, server.requestCount)
            return base.plusSeconds(readings++.toLong())
        }
    }
}
