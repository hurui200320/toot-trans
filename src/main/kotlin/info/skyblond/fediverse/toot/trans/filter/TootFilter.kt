package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.TranslationChainState
import info.skyblond.fediverse.toot.trans.masto.statuses.findTranslationChainState
import org.jsoup.Jsoup

/**
 * Parked reference for the future ancestry-context stage, superseded at
 * runtime by `PendingQueue.feed` in `masto/statuses/PendingQueue.kt`.
 *
 * It is kept (deprecated and not wired into the object graph) for its
 * design notes on the translated-ancestry prerequisite, and for the TODOs
 * on [ancestorsTranslated] about how the ancestry context should be built
 * once the translation stage lands: an original is eligible only when
 * every in-scan ancestor that itself needs translation already has a
 * complete chain ([TranslationChainState.Translated]). The LLM history
 * pairs an ancestor's original (user turn) with its translation (assistant
 * turn), so a child must not overtake an untranslated ancestor. The
 * runtime path currently serializes chains differently: the pending queue
 * orders by creation time, so parents come first, and the processing loop
 * stops at the first not-yet-cooled entry instead of filtering descendants
 * out.
 *
 * The basic read-only checks it shares with the runtime path — not already
 * in the target language, no footer, not blank in both body and content
 * warning — live in [requiresTranslation].
 * */
@Deprecated(
    "Parked for the future ancestry-context stage; superseded by PendingQueue",
    level = DeprecationLevel.HIDDEN
)
class TootFilter(
    private val targetLangCode: String,
) {
    init {
        require(targetLangCode.isNotBlank()) { "targetLangCode must not be blank" }
    }

    /**
     * Whether [status] is a toot this deployment would translate: not
     * already in the target language, not a translation reply (valid
     * footer), and not blank in both body and content warning. Cooldown
     * and ancestry are checked by the caller; the runtime twin is
     * `PendingQueue.feed`.
     * */
    private fun requiresTranslation(status: Status): Boolean {
        // already in target lang
        if (status.language == targetLangCode) return false
        // has footer
        if (status.content?.matchFooterFromContent() != null) return false
        // body and content warning is all blank
        val body = Jsoup.parseBodyFragment(status.content.orEmpty()).body().text()
        return body.isNotBlank() || !status.spoilerText.isNullOrBlank()
    }

    /**
     * The translated-ancestry prerequisite, see the class KDoc: walk the
     * parent chain inside the scan and require every ancestor that
     * [requiresTranslation] to already be translated. Ancestors outside
     * the scan end the walk: they are assumed settled, because their
     * chains cannot be resolved in memory and they are outside the context
     * design's window. This deliberately serializes chains: a fresh thread
     * advances one generation per run. The ancestor classification adds no
     * API calls; it is memoized across candidates via [chainStates].
     * */
    // TODO: find and rebuild translated ancestors with limit?
    //       live 5 level?
    // TODO: how do we find ancestors and their translations?
    //       ancestors are easy to find, just build reply chain,
    //       but finding translations as comment requires API calls
    //       as the ancestors and their comments may not in our scan result.
    //       maybe just include the original text? And ask LLM to quote original text?
    //       Like translating 林檎, LLM should output `apple (林檎)` if it's not confident?
    //       or just with best efforts?
    private fun ancestorsTranslated(
        status: Status,
        statusesMap: Map<String, Status>,
        chainStates: MutableMap<String, TranslationChainState>,
    ): Boolean {
        val visited = hashSetOf(status.id)
        var ancestor = statusesMap[status.inReplyToId]
        while (ancestor != null) {
            // the reply-chain walk rejects cycles; fail closed anyway
            if (!visited.add(ancestor.id)) return false
            if (requiresTranslation(ancestor)) {
                val state = chainStates.getOrPut(ancestor.id) {
                    findTranslationChainState(ancestor.id, statusesMap.values, targetLangCode)
                }
                if (state !is TranslationChainState.Translated) return false
            }
            ancestor = statusesMap[ancestor.inReplyToId]
        }
        return true
    }
}
