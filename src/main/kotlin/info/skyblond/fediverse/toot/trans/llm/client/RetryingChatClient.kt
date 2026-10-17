package info.skyblond.fediverse.toot.trans.llm.client

import info.skyblond.fediverse.toot.trans.config.requireValidRetryDelay
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [ChatClient] wrapper that retries transient completion failures on a **fixed
 * delay** between attempts: no exponential backoff, no jitter, and no `Retry-After`
 * honor — the project decision is to keep the policy trivial and let long-horizon
 * retries happen naturally, because a failed translation is simply attempted again
 * by the next polling cycle. The fixed spacing matches the boot-up scan's transient
 * retry style (see `performInitialScan` in `Scan.kt`).
 *
 * Retryable failures are transport errors ([IOException], which includes connect
 * and read timeouts) and provider-side transient API responses: 408 and 429, plus
 * every 5xx (overloaded or otherwise failing providers). 402 (quota / subscription
 * problems) and 403 (permission or region block) are not retried: both are
 * permanent until a human acts, so retrying cannot succeed and would only burn
 * the attempt budget — the failure propagates, and the next polling cycle
 * retries naturally. Everything else propagates immediately: other 4xx (invalid
 * key, bad request, unsupported model), malformed success bodies
 * ([kotlinx.serialization.SerializationException]), and cancellation — retrying
 * cannot fix them, and a stuck caller would hide a config bug.
 *
 * [maxAttempts] counts the initial attempt, so `maxAttempts = 4` means up to three
 * retries; the retry budget bounds how long one translation can wait before the
 * caller (the polling loop) sees the failure. The same request is resent, which is
 * safe because [ChatCompletionRequest] is immutable and replayable.
 *
 * Cancellation propagates at any point, including while waiting [retryDelay].
 * */
class RetryingChatClient(
    private val delegate: ChatClient,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelay: Duration = DEFAULT_RETRY_DELAY,
) : ChatClient {

    init {
        require(maxAttempts >= 1) {
            "maxAttempts must be at least 1 (the initial attempt), got $maxAttempts"
        }
        // shared with the app's transient retry contract, see
        // config/Validation.kt
        requireValidRetryDelay("retryDelay", retryDelay)
    }

    override suspend fun complete(request: ChatCompletionRequest): ChatCompletionResponse {
        var attempt = 1
        while (true) {
            try {
                return delegate.complete(request)
            } catch (e: ChatApiException) {
                if (!isRetryable(e) || attempt >= maxAttempts) throw e
                logger.warn(e) {
                    "Chat completion attempt $attempt/$maxAttempts failed " +
                            "(HTTP ${e.httpCode}: ${e.detail}), retrying in $retryDelay"
                }
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw e
                logger.warn(e) {
                    "Chat completion attempt $attempt/$maxAttempts failed (transport), retrying in $retryDelay"
                }
            }
            attempt++
            delay(retryDelay)
        }
    }

    /**
     * Transient provider responses, see the class KDoc for why each is retried.
     * */
    private fun isRetryable(error: ChatApiException): Boolean = when (error.httpCode) {
        408, 429 -> true
        else -> error.httpCode in 500..599
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * One initial attempt plus three retries. Programmatic default for the
         * constructor parameter: the application container does not use it, it
         * derives the attempt budget from the global transient retry budget
         * instead (see `di/AppModule.kt`), so this constant only serves direct
         * construction, tests included.
         * */
        const val DEFAULT_MAX_ATTEMPTS = 4

        /**
         * Fixed spacing between attempts; same order of magnitude as the boot-up
         * scan's transient retry delay. Programmatic default for the constructor
         * parameter, same container override as [DEFAULT_MAX_ATTEMPTS].
         * */
        val DEFAULT_RETRY_DELAY: Duration = 10.seconds
    }
}
