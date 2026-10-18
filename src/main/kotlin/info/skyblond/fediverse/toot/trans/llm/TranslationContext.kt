package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.client.ContentPart
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusSource

/**
 * The prompt input of one translation: the toot to translate plus its
 * reply-chain ancestry, oldest first.
 *
 * Built by `ReplyChainContext` in `masto/statuses/` from the pending
 * queue, the status pool and `/statuses/:id/source`, and consumed by
 * [TranslationRequestFactory].
 * */
data class TranslationContext(
    /**
     * The chain, root first; the last entry is the toot to translate
     * ([target]).
     * */
    val entries: List<TranslationContextEntry>,

    /**
     * Images of the toot to translate, downloaded for the vision
     * input (see [ContentPart.image]); empty when there are none or
     * none could be loaded. Ancestors contribute text only.
     * */
    val images: List<ContentPart.ImagePart> = emptyList(),
) {
    init {
        require(entries.isNotEmpty()) { "entries must not be empty" }
    }

    /** The toot this context translates. */
    val target: TranslationContextEntry get() = entries.last()
}

/**
 * One toot of a [TranslationContext]: its composing [source] and, for
 * an ancestor with a complete chain, the existing [translation].
 * */
data class TranslationContextEntry(
    /**
     * The fetched status, for the metadata only the entity carries
     * (link preview card, media attachments).
     * */
    val status: Status,

    /**
     * Composing text from `GET /api/v1/statuses/:id/source`; the
     * rendered [Status.content] HTML is deliberately not used.
     * */
    val source: StatusSource,

    /**
     * The ancestor's existing translation with its footer stripped,
     * in segment order; null when the ancestor has no complete chain
     * (see `TranslationChainState`), which the request factory renders
     * as [MISSING_TRANSLATION_PLACEHOLDER].
     * */
    val translation: String? = null,

    /**
     * The translated content warning of the ancestor's chain (the
     * first segment's spoiler text), when it had one.
     * */
    val translationSpoilerText: String? = null,
) {
    init {
        require(translationSpoilerText == null || translation != null) {
            "translationSpoilerText requires a translation"
        }
    }
}

/**
 * Assistant-turn placeholder of an ancestor whose translation is not
 * available (no complete chain), e.g. a toot older than the scan
 * window. Keeping the turn in place preserves the user/assistant
 * pair structure the prompt contract describes.
 * */
const val MISSING_TRANSLATION_PLACEHOLDER = "<missing translation>"

/**
 * User-turn text of a toot with neither body nor content warning (e.g.
 * a media-only ancestor), so the model can tell an empty toot from a
 * message whose content got lost; [TranslationRequestFactory] renders
 * it instead of an empty `"text"` field.
 * */
const val EMPTY_CONTENT_PLACEHOLDER = "<empty content>"
