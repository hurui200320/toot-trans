package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.config.requireValidCooldown
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * The translation cooldown: a scanned toot is only handed to the translation
 * stage once the version in hand is known to be final.
 *
 * A toot's effective time is `editedAt ?: createdAt`: an edit resets the
 * cooldown, because it changes what would be translated. A toot counts as
 * cooled down when it was fetched at least [duration] after that effective
 * time, so the fetched version is the settled one. The check compares
 * against [FetchedStatus.fetchedAt] instead of wall-clock now, so a version
 * fetched early within the window cannot be mistaken for a later, final one.
 * Future effective times (clock skew, or an instance clock ahead) stay
 * cooling until fetched at least [duration] after them. [Duration.ZERO]
 * disables the wait for everything except those future timestamps.
 *
 * [duration] must be finite and non-negative (fail-fast); the built-in
 * default is single-sourced in the companion object, and
 * `DefaultConfigProvider` reuses it so the deploy default cannot drift
 * apart from the programmatic/test default.
 *
 * The class is stateless: one instance can serve concurrent callers.
 * */
class Cooldown(
    private val duration: Duration = DEFAULT_DURATION,
) {
    init {
        requireValidCooldown("duration", duration)
    }

    /**
     * Whether [fetched] may be processed now, see the class KDoc for the
     * timing contract.
     * */
    fun checkCooldown(fetched: FetchedStatus): Boolean {
        val wait = duration.toJavaDuration()
        val effectiveTime = fetched.status.editedAt ?: fetched.status.createdAt

        // we must fetch the toot after the cooldown period,
        // so we know the version we got is after the cooldown and is final.
        if (java.time.Duration.between(effectiveTime, fetched.fetchedAt) < wait) {
            // still in cooldown when we fetch:
            // we don't know if there are edits in the remaining cooldown period
            return false
        }

        // since we fetch then check, it means now >= fetchedAt.
        // so now >= fetchedAt >= effectiveTime + cooldown
        return true
    }

    companion object {
        /**
         * Built-in default cooldown. Single source of truth, same pattern
         * as [StatusesCachePool]'s and
         * [info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff]'s
         * companion objects: the config package's default provider reuses
         * it, so the deploy default cannot drift apart from the
         * programmatic/test default.
         * */
        val DEFAULT_DURATION: Duration = 3.minutes
    }
}
