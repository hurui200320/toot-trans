package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.client.ChatApiException
import info.skyblond.fediverse.toot.trans.llm.client.ChatClient
import info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionException
import info.skyblond.fediverse.toot.trans.llm.client.firstChoiceText
import info.skyblond.fediverse.toot.trans.llm.client.isContentFiltered
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Turns one [TranslationContext] into a [TranslatedToot]: walks
 * [TranslationRequestFactory]'s request fallback sequence over [client]
 * and parses the answer.
 *
 * ## Fallback sequence
 *
 * The richest payload is tried first — full (history + context +
 * images) when the context carries images, text otherwise — and each
 * *payload-shaped* rejection (the request content is the problem, e.g.
 * a model without vision or a context window too small; the codes are
 * single-sourced in [ChatApiException.PAYLOAD_REJECTION_HTTP_CODES])
 * downgrades to the next variant, once, down to the toot-only minimal
 * request. Every other provider failure propagates untouched:
 * retryable ones (408, 429, 5xx) were already retried by
 * [info.skyblond.fediverse.toot.trans.llm.client.RetryingChatClient],
 * and non-payload 4xx (auth, quota, unknown model) will not get better
 * with a smaller body.
 *
 * An *answer-shaped* failure — [TranslationOutputException] from
 * parsing or the source-shape policy, or [ChatCompletionException]
 * from a completion without usable text — says nothing about the
 * payload, so the same variant is attempted up to [MAX_ANSWER_ATTEMPTS]
 * times (fresh completions, not re-parses of the same answer) before
 * the walk downgrades; a flaky answer thus never forces a smaller
 * request. A content-filtered answer
 * ([ChatCompletionException.isContentFiltered]) is the exception: the
 * provider refuses that payload deterministically, so it downgrades
 * right away instead of spending the attempts. A toot whose whole
 * fallback sequence is exhausted surfaces the last failure; the
 * processor then decides its fate.
 *
 * ## Answer policy
 *
 * The answer is parsed by [parseTranslationOutput] and then shaped
 * against the target source, which is the authority on the reply's
 * shape: a field the source toot does not have is nulled out — a
 * `spoiler_text` answered for a source without a warning is dropped,
 * and an answered body for a body-less source is ignored — while an
 * answer that dropped a field the source has is no translation at
 * all: a null content for a source with a body, or a null
 * `spoiler_text` for a source with a warning, fails as
 * [TranslationOutputException] instead of posting a body-less reply
 * or silently dropping the warning. A source with nothing to
 * translate (neither body nor warning) keeps the contract's all-null
 * answer.
 * */
class Translator(
    private val client: ChatClient,
    private val requestFactory: TranslationRequestFactory,
) {
    /**
     * Translate [context], or throw: [ChatApiException] for provider
     * failures, [TranslationOutputException] for unusable answers,
     * [info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionException]
     * for completions without usable text. Answer-shaped failures are
     * retried and downgraded first, see the class KDoc.
     * */
    suspend fun translate(context: TranslationContext): TranslatedToot {
        // full and text are the same request without images, so the
        // image-less context skips straight to text
        val requests = buildList {
            if (context.images.isNotEmpty()) {
                add("full" to requestFactory.createFullRequest(context))
            }
            add("text" to requestFactory.createTextRequest(context))
            add("minimal" to requestFactory.createMinimalRequest(context))
        }

        // the last failure of the walk; never null by the end because
        // the minimal variant records before it falls through
        var lastFailure: Exception? = null
        requests.forEachIndexed { index, (variant, request) ->
            val hasDowngradeLeft = index < requests.lastIndex
            for (attempt in 1..MAX_ANSWER_ATTEMPTS) {
                try {
                    val text = client.complete(request).firstChoiceText()
                    return applySourceShape(context, text, parseTranslationOutput(text))
                } catch (e: ChatApiException) {
                    if (!isDowngradable(e)) throw e
                    lastFailure = e
                    logger.warn {
                        "Provider rejected the $variant translation request " +
                                "(HTTP ${e.httpCode}: ${e.detail}), ${downgradeAction(hasDowngradeLeft)}"
                    }
                    // the payload did not change: re-sending it would
                    // only be rejected again, downgrade right away
                    break
                } catch (e: TranslationOutputException) {
                    lastFailure = e
                    logUnusableAnswer(variant, attempt, hasDowngradeLeft, e)
                } catch (e: ChatCompletionException) {
                    lastFailure = e
                    if (e.isContentFiltered()) {
                        // the provider refuses this payload
                        // deterministically: a fresh completion would
                        // only be filtered again, downgrade right away
                        logger.warn {
                            "Provider filtered the $variant translation answer " +
                                    "(attempt $attempt/$MAX_ANSWER_ATTEMPTS): ${e.message}, " +
                                    downgradeAction(hasDowngradeLeft)
                        }
                        break
                    }
                    logUnusableAnswer(variant, attempt, hasDowngradeLeft, e)
                }
            }
        }
        // the fallback sequence always ends with the minimal variant,
        // so a failure was recorded before this line is reachable
        throw requireNotNull(lastFailure)
    }

    /**
     * Log one answer-shaped failure with what the walk does next:
     * retry the same variant while attempts remain, downgrade while
     * variants remain, surface the failure otherwise.
     * */
    private fun logUnusableAnswer(
        variant: String,
        attempt: Int,
        hasDowngradeLeft: Boolean,
        failure: Exception,
    ) {
        logger.warn {
            val action = if (attempt < MAX_ANSWER_ATTEMPTS) {
                "retrying the same request"
            } else {
                downgradeAction(hasDowngradeLeft)
            }
            "Unusable answer from the $variant translation request " +
                    "(attempt $attempt/$MAX_ANSWER_ATTEMPTS): ${failure.message}, $action"
        }
    }

    /**
     * The walk's next step, for the log, after a failure that ends the
     * current request variant: downgrade to the next one, or surface
     * the failure when no variant is left.
     * */
    private fun downgradeAction(hasDowngradeLeft: Boolean): String =
        if (hasDowngradeLeft) "downgrading" else "no further downgrade available"

    /**
     * Whether [error] means "the payload shape is the problem" and the
     * next request variant is worth one attempt; the codes are
     * single-sourced in [ChatApiException.PAYLOAD_REJECTION_HTTP_CODES],
     * see the class KDoc.
     * */
    private fun isDowngradable(error: ChatApiException): Boolean =
        error.httpCode in ChatApiException.PAYLOAD_REJECTION_HTTP_CODES

    /**
     * Null the answer fields the source toot does not have, and reject
     * an answer that dropped a field the source has: see the class
     * KDoc's answer policy. [context]'s target source is the authority
     * on both the reply's shape (body-less or not) and whether a
     * content warning exists; [text] feeds the failure snippet, like
     * the parse failures of [parseTranslationOutput].
     * */
    private fun applySourceShape(
        context: TranslationContext,
        text: String,
        parsed: TranslatedToot,
    ): TranslatedToot {
        val source = context.target.source
        if (source.text.isNotBlank() && parsed.content == null) {
            throw TranslationOutputException(
                "Translation answer has no \"content\" for toot ${context.target.status.id} " +
                        "with a body: ${text.take(SNIPPET_LENGTH)}"
            )
        }
        if (source.spoilerText.isNotBlank() && parsed.spoilerText == null) {
            throw TranslationOutputException(
                "Translation answer has no \"spoiler_text\" for toot ${context.target.status.id} " +
                        "with a content warning: ${text.take(SNIPPET_LENGTH)}"
            )
        }
        return TranslatedToot(
            content = parsed.content.takeIf { source.text.isNotBlank() },
            spoilerText = parsed.spoilerText.takeIf { source.spoilerText.isNotBlank() },
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Attempts of one request variant for an answer-shaped failure:
         * the initial completion plus three retries. Each attempt is a
         * fresh completion — the model may simply have strayed once —
         * and the budget mirrors `RetryingChatClient`'s
         * `DEFAULT_MAX_ATTEMPTS` idiom (the initial attempt counted).
         * Failures a fresh completion cannot fix are not re-attempted:
         * payload-shaped rejections (the payload did not change) and
         * content-filtered answers (the provider refuses that payload
         * deterministically, see [ChatCompletionException.isContentFiltered]).
         * */
        private const val MAX_ANSWER_ATTEMPTS = 4
    }
}
