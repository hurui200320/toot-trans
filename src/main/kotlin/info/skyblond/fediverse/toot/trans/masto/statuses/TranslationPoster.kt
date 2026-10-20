package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_MAX_RETRY
import info.skyblond.fediverse.toot.trans.DEFAULT_TRANSIENT_RETRY_DELAY
import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.llm.MAX_TOOT_CHARS
import info.skyblond.fediverse.toot.trans.llm.TranslatedToot
import info.skyblond.fediverse.toot.trans.llm.chunkTranslation
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.client.statuses.CreateStatusRequest
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.io.IOException
import java.time.Clock
import kotlin.time.Duration

/**
 * Posts one [TranslatedToot] as the reply chain of its original toot:
 * the segments from [chunkTranslation], first at the original, every
 * following one at the previous segment, so the chain stays connected
 * exactly as `TranslationChain` reconstructs it. A body-less
 * translation — [TranslatedToot.content] null, i.e. a toot whose only
 * text is its content warning (see `Translator`'s answer policy) — is
 * one footer-only segment with the translated warning attached: the
 * instance rejects body-less statuses, and the warning is the
 * translation.
 *
 * Wire rules ([StatusesClient.createStatus] owns the protocol):
 * - `language` is [targetLangCode] on every segment, so the next scan
 *   recognizes its own replies;
 * - `spoiler_text` (the translated content warning) rides on the first
 *   segment only, matching the design; `sensitive` is copied from the
 *   original;
 * - visibility: a `public` original yields `unlisted` replies (readers
 *   can follow the thread without the translation flooding public
 *   timelines); every other visibility is kept as-is. A `direct`
 *   original's reply cannot reach the original's mentioned accounts
 *   (no mentions are copied), so it stays visible to this account
 *   only; see the README's known limitations;
 * - `requestId` is the original's status id on every segment: the
 *   instance's `Idempotency-Key` derivation already includes the
 *   differing form fields, so a retried segment cannot duplicate while
 *   equal replies to different originals cannot collide.
 *
 * Failures: a 429 waits out the instance's reset via [rateLimitBackoff]
 * and retries the same segment without bound (every request is
 * idempotent by key); 5xx responses and transport errors retry within
 * the shared transient retry budget ([transientMaxRetry],
 * [transientRetryDelay]); anything else propagates, leaving whatever
 * was already posted in place. That partial chain is exactly what the
 * next run's chain classification reports as invalid and cleans up
 * leaf-first ([StatusesDeleter]) before retranslating.
 *
 * Every created segment is seeded into [pool] with [clock]'s fetch
 * instant, so a descendant processed later in the same run sees its
 * ancestor's fresh translation through
 * [PendingQueue.findTranslationChainState].
 * */
class TranslationPoster(
    private val client: StatusesClient,
    private val pool: StatusesCachePool,
    private val targetLangCode: String,
    private val rateLimitBackoff: RateLimitBackoff = RateLimitBackoff(),
    private val transientMaxRetry: Int = DEFAULT_TRANSIENT_MAX_RETRY,
    private val transientRetryDelay: Duration = DEFAULT_TRANSIENT_RETRY_DELAY,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(targetLangCode.isNotBlank()) { "targetLangCode must not be blank" }
        require(transientMaxRetry >= 0) { "transientMaxRetry must be non-negative, got $transientMaxRetry" }
        require(transientRetryDelay.isFinite() && transientRetryDelay.inWholeMilliseconds > 0) {
            "transientRetryDelay must be finite and at least 1ms, got $transientRetryDelay"
        }
    }

    /**
     * Post [translated] under [original], in order, and return the
     * created segments. Fails fast (see the class KDoc) after having
     * posted zero or more segments.
     *
     * A null [TranslatedToot.content] means the source has no body
     * (see `Translator`): the reply is then the footer alone, carrying
     * the translated warning, and a warning that leaves no room within
     * the char limit fails fast like the chunked budget check (see
     * [bodyLessSegments]). A translation with neither body nor warning
     * has nothing to post and fails fast — the entrance filter and the
     * translator's answer policy never produce one.
     * */
    suspend fun post(original: Status, translated: TranslatedToot): List<Status> {
        require(original.id.isNotBlank()) { "original.id must not be blank" }
        require(translated.content != null || translated.spoilerText != null) {
            "translation of ${original.id} has neither a body nor a content warning, nothing to post"
        }
        val hash = original.crc32()
        val segments = translated.content?.let { content ->
            chunkTranslation(
                translation = content,
                spoilerText = translated.spoilerText,
                hash = hash,
            )
        } ?: bodyLessSegments(hash, translated.spoilerText)
        val created = mutableListOf<Status>()
        var inReplyToId = original.id
        segments.forEachIndexed { index, segment ->
            val draft = CreateStatusRequest(
                status = segment,
                requestId = original.id,
                inReplyToId = inReplyToId,
                language = targetLangCode,
                visibility = replyVisibility(original.visibility),
                spoilerText = translated.spoilerText?.takeIf { index == 0 },
                sensitive = original.sensitive,
            )
            val status = createWithRetry(draft)
            logger.info { "Posted translation segment ${index + 1}/${segments.size} of ${original.id}: id=${status.id}" }
            created += status
            inReplyToId = status.id
            pool.seed(listOf(FetchedStatus(status, clock.instant())))
        }
        return created
    }

    /**
     * The segments of a body-less translation: one footer-only
     * segment, because the instance rejects a status with no text at
     * all. The translated warning is the only content left, so its
     * length is checked against [MAX_TOOT_CHARS] here — the
     * footer-only twin of `chunkTranslation`'s content-warning budget
     * check — and an oversized warning fails fast instead of being
     * sent for the instance to reject.
     * */
    private fun bodyLessSegments(hash: String, spoilerText: String?): List<String> {
        val footer = Footer(hash, 1, 1).toString()
        val warningLength = spoilerText?.length ?: 0
        require(warningLength + footer.length <= MAX_TOOT_CHARS) {
            "content warning of $warningLength chars leaves no room for the footer-only reply " +
                    "within the $MAX_TOOT_CHARS char limit"
        }
        return listOf(footer)
    }

    /**
     * Post one segment, retrying the same request per the class KDoc's
     * failure policy. Only [MastoApiException] and [IOException] are
     * caught, so cancellation (including during a backoff [delay])
     * propagates.
     * */
    private suspend fun createWithRetry(draft: CreateStatusRequest): Status {
        var transientRetries = 0
        while (true) {
            try {
                return client.createStatus(draft)
            } catch (e: MastoApiException) {
                if (e.httpCode == 429) {
                    val wait = rateLimitBackoff.waitDuration(e)
                    logger.warn {
                        "Rate limited posting reply, backing off $wait " +
                                "(reset: ${e.rateLimitReset ?: "no usable header"})"
                    }
                    delay(wait)
                } else if (e.httpCode >= 500 && transientRetries < transientMaxRetry) {
                    transientRetries++
                    logger.warn(e) {
                        "Server error posting reply, retry $transientRetries/$transientMaxRetry in $transientRetryDelay"
                    }
                    delay(transientRetryDelay)
                } else {
                    throw e
                }
            } catch (e: IOException) {
                if (transientRetries >= transientMaxRetry) throw e
                transientRetries++
                logger.warn(e) {
                    "Transport error posting reply, retry $transientRetries/$transientMaxRetry in $transientRetryDelay"
                }
                delay(transientRetryDelay)
            }
        }
    }

    /**
     * Visibility of the replies: `public` becomes `unlisted`, anything
     * else is kept; see the class KDoc.
     * */
    private fun replyVisibility(original: String): String =
        if (original == "public") "unlisted" else original

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
