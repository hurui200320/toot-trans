package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.llm.TranslatedToot
import info.skyblond.fediverse.toot.trans.llm.Translator
import info.skyblond.fediverse.toot.trans.llm.TranslationContext
import info.skyblond.fediverse.toot.trans.llm.TranslationOutputException
import info.skyblond.fediverse.toot.trans.llm.client.ChatApiException
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionException
import info.skyblond.fediverse.toot.trans.llm.client.isRetryable
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown
import info.skyblond.fediverse.toot.trans.masto.statuses.PendingQueue
import info.skyblond.fediverse.toot.trans.masto.statuses.ReplyChainContext
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesDeleter
import info.skyblond.fediverse.toot.trans.masto.statuses.TranslationChainState
import info.skyblond.fediverse.toot.trans.masto.statuses.TranslationPoster
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Drains [PendingQueue]: for every eligible original, delete obsolete
 * segments, build the LLM context, translate, log the result for
 * review, post the translation as the original's reply chain
 * ([TranslationPoster]) and drop the entry; the translation segments
 * themselves are chain material and are simply dropped when they reach
 * the head.
 *
 * The queue is ordered by creation time, so ancestors are always
 * translated before their replies; a toot still inside [Cooldown]
 * stops the drain and keeps its position, so the rolling cursor never
 * advances past something unprocessed. When a failure stops the drain,
 * the entry stays queued and the next drain cycle retries it; the scan
 * loop re-fetches from the cursor and refreshes the queued copy first
 * (the window lives in memory only). The one exception is the loop's
 * no-cursor bootstrap: when nothing was ever dropped, it sacrifices the
 * head to establish the cursor, see `PendingQueue.dropHeadAndRecordCursor`.
 *
 * Failure policy (see also [Translator] and [TranslationPoster]):
 * - toot-specific failures — the answer cannot be parsed, the
 *   completion is unusable, the provider permanently rejects the
 *   payload after the request fallback sequence, the instance
 *   permanently rejects the reply payload (HTTP 400/422, e.g. a
 *   custom character limit below the segment budget), or a chain
 *   status is gone from the instance (a source read answering 404,
 *   e.g. deleted between the scan and the drain) — drop the entry
 *   with a loud log, so the queue keeps moving;
 * - credential/configuration failures (an LLM 4xx outside the payload
 *   set, e.g. a bad key or unknown model, or an instance 401/403, e.g.
 *   a revoked token or one without `write:statuses`) propagate: every
 *   following toot would fail the same way;
 * - everything else (rate limits, server errors, transport errors)
 *   stops the drain and leaves the entry queued for the next drain
 *   cycle. A posting failure leaves the already-posted prefix in place;
 *   the next cycle's chain classification reports the partial chain as
 *   invalid and deletes it leaf-first before retranslating.
 * */
class TranslationProcessor(
    private val queue: PendingQueue,
    private val cooldown: Cooldown,
    private val deleter: StatusesDeleter,
    private val contextBuilder: ReplyChainContext,
    private val translator: Translator,
    private val poster: TranslationPoster,
) {
    /**
     * Process the window until it is empty, the head is blocked, or a
     * failure requires stopping. Idempotent: a second call continues
     * where the first stopped.
     * */
    suspend fun processQueue() {
        logger.info { "Draining the pending queue" }
        while (queue.hasNext()) {
            val fetched = queue.next()
            val toot = fetched.status

            // a translation segment is chain material, never work
            if (queue.isTranslation(toot.id)) {
                queue.drop(toot.id)
                continue
            }

            if (!cooldown.checkCooldown(fetched)) {
                logger.info { "Toot ${toot.id} is still cooling down, stopping this drain" }
                break
            }

            try {
                processOne(toot)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    isTootSpecific(e) -> {
                        logger.error(e) { "Dropping toot ${toot.id} after a permanent failure" }
                        queue.drop(toot.id)
                    }

                    e is ChatApiException && !e.isRetryable() -> {
                        // a credential/configuration problem: every
                        // following toot would fail identically, so fail
                        // fast instead of logging the same error per toot
                        throw e
                    }

                    e is MastoApiException && e.httpCode in CREDENTIAL_HTTP_CODES -> {
                        // the instance-side twin: our token is invalid
                        // or lacks the needed scope, so every following
                        // toot would fail identically
                        throw e
                    }

                    else -> {
                        logger.warn(e) {
                            "Stopping the drain at toot ${toot.id}; it stays queued for the next drain cycle"
                        }
                        break
                    }
                }
            }
        }
        if (queue.hasNext()) {
            logger.info { "Drain stopped early, queued entries remain for the next drain cycle" }
        } else {
            logger.info { "Queue drain finished" }
        }
    }

    /**
     * Translate one eligible original: delete obsolete segments
     * leaf-first, then context -> translate -> log -> post -> drop.
     * Failures propagate to [processQueue]'s policy.
     * */
    private suspend fun processOne(toot: Status) {
        when (val state = queue.findTranslationChainState(toot)) {
            is TranslationChainState.Translated -> {
                logger.info { "Already translated: id=${toot.id}, segments=${state.chain.segments.size}" }
                queue.drop(toot.id)
            }

            is TranslationChainState.NeedsTranslation -> {
                if (state.obsolete.isNotEmpty()) {
                    // leaf-first, as ordered by findTranslationChainState
                    deleter.delete(state.obsolete)
                    logger.info { "Deleted obsolete segments: ${state.obsolete.joinToString { it.id }}" }
                }
                logger.info { "Translating: id=${toot.id}, " +
                        "reason=${state.reason}, obsolete=${state.obsolete.size}, " +
                        "cw=${toot.spoilerText}, content=${toot.content}" }
                val context = contextBuilder.build(toot)
                val translated = translator.translate(context)
                logTranslation(toot, state.reason, context, translated)
                val posted = poster.post(toot, translated)
                logger.info { "Posted ${posted.size} translation segment(s) for toot ${toot.id}" }
                queue.drop(toot.id)
            }
        }
    }

    /**
     * Log one finished translation: the source and the answer are both
     * included, so the result can be reviewed (and the prompt logic
     * tweaked) straight from the log, without a second lookup — also
     * when the instance later rejects the post. One block per toot,
     * framed by markers for easy grepping; the posted segments are
     * logged individually by [TranslationPoster].
     * */
    private fun logTranslation(
        toot: Status,
        reason: TranslationChainState.Reason,
        context: TranslationContext,
        translated: TranslatedToot,
    ) {
        logger.info {
            """
            |===== translation of ${toot.id} (reason=$reason) =====
            |source spoiler_text: ${context.target.source.spoilerText.ifBlank { "<none>" }}
            |source:
            |${context.target.source.text.ifBlank { "<none>" }}
            |------------------------------------------------------
            |translation spoiler_text: ${translated.spoilerText ?: "<none>"}
            |translation:
            |${translated.content ?: "<none>"}
            |===== end of translation of ${toot.id} =====
            """.trimMargin()
        }
    }

    /**
     * Whether [e] means this one toot cannot be translated as-is, while
     * the rest of the queue is unaffected; see the class KDoc.
     * */
    private fun isTootSpecific(e: Throwable): Boolean = when (e) {
        // a malformed response body is a provider/instance fault, not a
        // toot fault, so it must stop the drain instead of dropping the
        // entry (SerializationException is an IllegalArgumentException)
        is SerializationException -> false

        is TranslationOutputException, is ChatCompletionException -> true

        // data-driven fail-fast checks of one toot (the segment budget
        // checks of TranslationChunks): the queue keeps moving
        is IllegalArgumentException -> true

        // the instance permanently rejects this toot: the status is
        // gone (404) or the reply payload is invalid (400/422, e.g. a
        // custom character limit below the segment budget); no later
        // run can post it as-is
        is MastoApiException -> e.httpCode in PERMANENT_TOOT_HTTP_CODES

        is ChatApiException -> e.httpCode in ChatApiException.PAYLOAD_REJECTION_HTTP_CODES

        else -> false
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Instance responses that mean this toot cannot be handled
         * as-is, for [isTootSpecific]: the status is gone (404) or the
         * payload is permanently invalid (400/422). 429 is absorbed by
         * the poster's own backoff and never reaches the policy.
         * */
        private val PERMANENT_TOOT_HTTP_CODES = setOf(400, 404, 422)

        /**
         * Instance responses that mean our token is invalid or lacks
         * the needed scope, for `processQueue`'s fail-fast branch:
         * every following toot would fail identically. 429 is absorbed
         * by the callers' own backoff and never reaches the policy.
         * */
        private val CREDENTIAL_HTTP_CODES = setOf(401, 403)
    }
}
