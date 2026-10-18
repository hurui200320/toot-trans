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
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException

/**
 * Drains [PendingQueue]: for every eligible original, delete obsolete
 * segments, build the LLM context, translate, log the result and drop
 * the entry; the translation segments themselves are chain material and
 * are simply dropped when they reach the head.
 *
 * The queue is ordered by creation time, so ancestors are always
 * translated before their replies; a toot still inside [Cooldown]
 * stops the drain and keeps its position, so the rolling cursor never
 * advances past something unprocessed. When a failure stops the drain,
 * the entry stays queued and the whole run is retried by the next
 * process start (the window is rebuilt in memory, there is no state on
 * disk).
 *
 * Failure policy (see also [Translator]):
 * - toot-specific failures — the answer cannot be parsed, the
 *   completion is unusable, the provider permanently rejects the
 *   payload after the request ladder, a chain status is gone from the
 *   instance (a source read answering 404, e.g. deleted between the
 *   scan and the drain) — drop the entry with a loud log, so the queue
 *   keeps moving;
 * - credential/configuration failures (an LLM 4xx outside the payload
 *   set, e.g. a bad key or unknown model) propagate: every following
 *   toot would fail the same way;
 * - everything else (rate limits, server errors, transport errors)
 *   stops the drain and leaves the entry queued for the next run.
 * */
class TranslationProcessor(
    private val queue: PendingQueue,
    private val cooldown: Cooldown,
    private val deleter: StatusesDeleter,
    private val contextBuilder: ReplyChainContext,
    private val translator: Translator,
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

                    else -> {
                        logger.warn(e) {
                            "Stopping the drain at toot ${toot.id}; it stays queued for the next run"
                        }
                        break
                    }
                }
            }
        }
        if (queue.hasNext()) {
            logger.info { "Drain stopped early, queued entries remain for the next run" }
        } else {
            logger.info { "Queue drain finished" }
        }
    }

    /**
     * Translate one eligible original: delete obsolete segments
     * leaf-first, then context -> translate -> log -> drop. Failures
     * propagate to [processQueue]'s policy.
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
                queue.drop(toot.id)
            }
        }
    }

    /**
     * Log one finished translation instead of posting it. The source
     * and the answer are both included, so the result can be reviewed
     * (and the prompt logic tweaked) straight from the log, without a
     * second lookup. One block per toot, framed by markers for easy
     * grepping.
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
            |${context.target.source.text}
            |translation spoiler_text: ${translated.spoilerText ?: "<none>"}
            |translation:
            |${translated.translation}
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

        // data-driven fail-fast checks of one toot (e.g. the chunking
        // limit of the posting milestone): the queue keeps moving
        is IllegalArgumentException -> true

        // the instance no longer has the status: no later run can
        // translate it
        is MastoApiException -> e.httpCode == 404

        is ChatApiException -> e.httpCode in ChatApiException.PAYLOAD_REJECTION_HTTP_CODES

        else -> false
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
