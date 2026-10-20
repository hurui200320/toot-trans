package info.skyblond.fediverse.toot.trans.masto

import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shared 429 backoff policy for this instance's API: turns a
 * rate-limited request into a wait duration. Callers on the same
 * token share the server's rate-limit buckets, so they back off
 * alike — currently [StatusesCachePool][info.skyblond.fediverse.toot.trans.masto.statuses.StatusesCachePool]'s
 * worker and the scans (see `Scan.kt`).
 *
 * The wait follows the instance's `X-RateLimit-Reset` header, exposed
 * on [MastoApiException.rateLimitReset]: a usable reset — parseable,
 * in the future, and no further out than [maxWaitDuration] — yields
 * `reset - now + [resetMargin]`; the margin absorbs clock skew between
 * this app and the instance. Windows can legitimately be long on
 * customized instances, so distance alone does not flag a broken
 * header; [maxWaitDuration] is the sanity line, beyond which (like
 * missing, unparseable, or already-past resets) the header counts as
 * unusable and the fixed [fallbackDelay] applies instead — so a
 * pathological timestamp cannot park a caller indefinitely.
 *
 * The class is stateless: one instance can serve concurrent callers.
 * [clock] supplies "now" for the reset comparison; tests inject a
 * controllable clock for deterministic waits.
 * https://docs.joinmastodon.org/api/rate-limits/
 * */
class RateLimitBackoff(
    private val resetMargin: Duration = DEFAULT_RESET_MARGIN,
    private val fallbackDelay: Duration = DEFAULT_FALLBACK_DELAY,
    private val maxWaitDuration: Duration = DEFAULT_MAX_WAIT_DURATION,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        // the messages name the offending setting so a bad value
        // surfaces in config errors (e.g. `backoffFallbackDelay=0s`),
        // like StatusesCachePool's own init checks
        require(fallbackDelay.isFinite() && fallbackDelay.inWholeMilliseconds > 0) {
            "fallbackDelay must be finite and at least 1ms"
        }
        require(maxWaitDuration.isFinite() && maxWaitDuration.inWholeMilliseconds > 0) {
            "maxWaitDuration must be finite and at least 1ms"
        }
        require(resetMargin.isFinite() && resetMargin >= Duration.ZERO) {
            "resetMargin must be finite and non-negative"
        }

        logger.info {
            "RateLimitBackoff initialized with " +
                    "resetMargin=$resetMargin, " +
                    "fallbackDelay=$fallbackDelay, " +
                    "maxWaitDuration=$maxWaitDuration"
        }
    }

    /**
     * How long to back off after a 429 [error]: the reset-based wait,
     * or [fallbackDelay] when the header is unusable (see the class
     * KDoc).
     * */
    fun waitDuration(error: MastoApiException): Duration = resetWait(error) ?: fallbackDelay

    private fun resetWait(error: MastoApiException): Duration? {
        val raw = error.rateLimitReset ?: return null
        val reset = try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            return null
        }
        val now = clock.instant()
        if (!reset.isAfter(now)) return null
        val wait = java.time.Duration.between(now, reset)
        if (wait > java.time.Duration.ofMillis(maxWaitDuration.inWholeMilliseconds)) return null
        return wait.seconds.seconds + (wait.nano / 1_000_000).milliseconds + resetMargin
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Big margin for a very inaccurate system clock. */
        val DEFAULT_RESET_MARGIN: Duration = 800.milliseconds

        val DEFAULT_FALLBACK_DELAY: Duration = 10.seconds

        /** Sanity bound on reset-based backoff, see the class KDoc. */
        val DEFAULT_MAX_WAIT_DURATION: Duration = 24.hours
    }
}
