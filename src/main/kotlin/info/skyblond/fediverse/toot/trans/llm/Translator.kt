package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.client.ChatApiException
import info.skyblond.fediverse.toot.trans.llm.client.ChatClient
import info.skyblond.fediverse.toot.trans.llm.client.firstChoiceText
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Turns one [TranslationContext] into a [TranslatedToot]: walks
 * [TranslationRequestFactory]'s request ladder over [client] and parses
 * the answer.
 *
 * ## Ladder
 *
 * The richest payload is tried first — full (history + context +
 * images) when the context carries images, text otherwise — and each
 * *payload-shaped* rejection (the request content is the problem, e.g.
 * a model without vision or a context window too small; the codes are
 * single-sourced in [ChatApiException.PAYLOAD_REJECTION_HTTP_CODES])
 * downgrades to the next variant, once, down to the toot-only minimal
 * request. Every other failure propagates untouched:
 * retryable ones (408, 429, 5xx) were already retried by
 * [info.skyblond.fediverse.toot.trans.llm.client.RetryingChatClient],
 * and non-payload 4xx (auth, quota, unknown model) will not get better
 * with a smaller body. A toot whose ladder is exhausted surfaces the
 * last rejection; the processor then decides its fate.
 *
 * ## Answer policy
 *
 * The answer is parsed by [parseTranslationOutput] and then adjusted
 * against the target source, which is the authority on the reply's
 * shape:
 * - a source with a body: the answered body is used as is; when the
 *   source has a content warning but the answer omitted it (blank or
 *   null `spoiler_text`), the original warning is kept instead of
 *   posting a warning-less reply — degraded, but never silently
 *   dropping the warning; a `spoiler_text` answered for a source
 *   without one is dropped;
 * - a content-warning-only source (blank body):
 *   [TranslationRequestFactory] sends its warning as the text, so the
 *   answered body is the translated warning and becomes the reply's
 *   `spoiler_text`, with a blank body — the reply mirrors the
 *   body-less source, with its warning translated instead of kept in
 *   the source language.
 * */
class Translator(
    private val client: ChatClient,
    private val requestFactory: TranslationRequestFactory,
) {
    /**
     * Translate [context], or throw: [ChatApiException] for provider
     * failures, [TranslationOutputException] for unusable answers,
     * [info.skyblond.fediverse.toot.trans.llm.client.ChatCompletionException]
     * for blank completions.
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

        var lastRejection: ChatApiException? = null
        requests.forEachIndexed { index, (variant, request) ->
            try {
                val answer = client.complete(request)
                return applySpoilerPolicy(context, parseTranslationOutput(answer.firstChoiceText()))
            } catch (e: ChatApiException) {
                if (!isDowngradable(e)) throw e
                lastRejection = e
                logger.warn {
                    val action = if (index < requests.lastIndex) {
                        "downgrading"
                    } else {
                        "no further downgrade available"
                    }
                    "Provider rejected the $variant translation request " +
                            "(HTTP ${e.httpCode}: ${e.detail}), $action"
                }
            }
        }
        // the ladder always ends with the minimal rung, so a rejection
        // was recorded before this line is reachable
        throw requireNotNull(lastRejection)
    }

    /**
     * Whether [error] means "the payload shape is the problem" and the
     * next ladder rung is worth one attempt; the codes are
     * single-sourced in [ChatApiException.PAYLOAD_REJECTION_HTTP_CODES],
     * see the class KDoc.
     * */
    private fun isDowngradable(error: ChatApiException): Boolean =
        error.httpCode in ChatApiException.PAYLOAD_REJECTION_HTTP_CODES

    /**
     * See the class KDoc's answer policy; [context]'s target source is
     * the authority on both the reply's shape (body-less or not) and
     * whether a content warning exists.
     * */
    // FIXME: review this, understand this first
    private fun applySpoilerPolicy(
        context: TranslationContext,
        parsed: TranslatedToot,
    ): TranslatedToot {
        val source = context.target.source
        // a content-warning-only source is sent as its warning text (see
        // the factory), so the answered body is the translated warning
        // and the reply mirrors the source: warning translated, no body
        if (source.text.isBlank() && source.spoilerText.isNotBlank()) {
            return TranslatedToot(
                translation = "",
                spoilerText = parsed.spoilerText ?: parsed.translation,
            )
        }
        val sourceWarning = source.spoilerText
        val spoilerText = when {
            sourceWarning.isBlank() ->
                // no warning in the source: a stray one is not part of the reply
                null

            parsed.spoilerText != null -> parsed.spoilerText

            else -> {
                logger.warn {
                    "Answer omitted the content warning of ${context.target.status.id}, keeping the original"
                }
                sourceWarning
            }
        }
        return TranslatedToot(parsed.translation, spoilerText)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
