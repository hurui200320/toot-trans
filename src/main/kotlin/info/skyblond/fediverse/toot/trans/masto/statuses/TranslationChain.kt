package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent

/**
 * The translation chain of one original toot: each segment's [Status] with
 * its parsed [Footer], in footer order (the 1-based `seq` of the marker),
 * as accepted by [findTranslationChainState]. Carrying the footer along
 * saves callers from re-parsing the rendered content.
 * */
data class TranslationChain(
    val segments: List<Pair<Status, Footer>>,
) {
    init {
        require(segments.isNotEmpty()) { "segments must not be empty" }
    }
}

/**
 * The reconstructed translation state of one original toot, see
 * [findTranslationChainState], which owns the classification contract.
 * */
sealed interface TranslationChainState {
    /**
     * Exactly one valid translation chain exists for the original. The
     * one-shot policy accepts it even when the original was edited after
     * the translation was posted; nothing needs to be deleted.
     * */
    data class Translated(val chain: TranslationChain) : TranslationChainState

    /**
     * No usable chain exists: a fresh translation must be posted.
     *
     * [obsolete] lists every discovered segment of this deployment in
     * leaf-first (deepest first) order; callers must delete them in that
     * order before posting the new chain. It is empty exactly when
     * [reason] is [Reason.ABSENT].
     * */
    data class NeedsTranslation(
        val reason: Reason,
        val obsolete: List<Status>,
    ) : TranslationChainState

    enum class Reason {
        /** No chain segments are linked to the original. */
        ABSENT,

        /** Segments exist, but they do not form one clean valid chain. */
        INVALID,
    }
}

/**
 * Reconstruct the translation chain of the original toot [originalId] from
 * the owner's already-fetched [statuses], and classify it. Pure: no API
 * call, no local state, no coroutines.
 *
 * A **segment** is a status carrying a valid footer (the
 * `🌐{hash}#{seq}#{total}` marker, see [Footer]), tagged [targetLangCode],
 * and naming a parent in `inReplyToId`.
 *
 * The input contract is the boot scan's: [statuses] must be the owner's own
 * statuses (authorship therefore needs no check), covering everything newer
 * than the oldest entry — the scan of `performInitialScan` in `Scan.kt`
 * fetches exactly that. Only then is a chain fully visible whenever its
 * original is. A partial (`since_id`-style) poll window does not qualify.
 * The scan yields each status once; input repeating a status id is out of
 * contract and fails closed: never [TranslationChainState.Translated], and
 * each id appears at most once in
 * [TranslationChainState.NeedsTranslation.obsolete].
 * Segments are linked via `inReplyToId`; segments tagged for another target
 * language belong to another deployment sharing the account and are
 * invisible here: they neither suppress nor get deleted. A segment whose
 * parent was deleted outside this application becomes unreachable and stays
 * untouched (manual recovery).
 *
 * The result is:
 * - [TranslationChainState.Translated] when the discovered segments are
 *   exactly one valid chain: a simple path from a direct reply (at most one
 *   child per status, no repeated status id) with consecutive footer `seq`
 *   values starting at 1, one common [Footer.hash] and [Footer.total], and
 *   exactly `total` segments. The one-shot policy deliberately ignores
 *   whether the footer hash still matches the current original
 *   ([Status.crc32]): an edited original keeps its existing chain, and the
 *   mismatch is left for readers to verify via the footer.
 * - [TranslationChainState.NeedsTranslation] otherwise, with every
 *   discovered segment in [TranslationChainState.NeedsTranslation.obsolete].
 *   Only one clean chain is acceptable: any extra segment — a partial post,
 *   a duplicate chain, or a leftover next to a valid chain (for example
 *   `#1#4`..`#4#4` plus a `#5#5`) — invalidates the whole set, because
 *   leftovers confuse readers; a valid chain is re-posted rather than kept.
 *   [TranslationChainState.Reason.ABSENT] means no segment was linked at
 *   all, so there is nothing to delete.
 *
 * The deletion order in `obsolete` is leaf-first (deepest segment first),
 * so a status is never left pointing at a parent that no longer exists
 * before being deleted itself. Delete first, then post the replacement:
 * see [TranslationChainState.NeedsTranslation].
 * */
fun findTranslationChainState(
    originalId: String,
    statuses: Collection<Status>,
    targetLangCode: String,
): TranslationChainState {
    require(originalId.isNotBlank()) { "originalId must not be blank" }
    require(targetLangCode.isNotBlank()) { "targetLangCode must not be blank" }
    // find all translated toots
    val segments = statuses.mapNotNull { status ->
        // only match our targeted lang
        if (status.language != targetLangCode) return@mapNotNull null
        // skip no parent toots
        val parentId = status.inReplyToId ?: return@mapNotNull null
        // require footer
        val footer = status.content?.matchFooterFromContent() ?: return@mapNotNull null
        ChainSegment(status, parentId, footer)
    }
    // parentId -> replies
    val childrenByParent = segments.groupBy { it.parentId }

    val roots = childrenByParent[originalId].orEmpty()
    if (roots.isEmpty()) { // no translated comments for the original toot
        return TranslationChainState.NeedsTranslation(
            reason = TranslationChainState.Reason.ABSENT,
            obsolete = emptyList(),
        )
    }

    // BFS: every segment reachable from the original, first visit wins
    val reachable = LinkedHashMap<String, LocatedSegment>()
    val queue = ArrayDeque<LocatedSegment>()
    // enqueue direct comments
    roots.forEach { queue.addLast(LocatedSegment(it, 1)) }
    while (queue.isNotEmpty()) {
        val (segment, depth) = queue.removeFirst()
        // the visited check dedupes repeated ids from duplicated input
        // entries; a well-formed scan never enqueues the same id twice
        if (reachable.containsKey(segment.status.id)) continue
        reachable[segment.status.id] = LocatedSegment(segment, depth)
        // search comments for current comment
        childrenByParent[segment.status.id].orEmpty().forEach { child ->
            queue.addLast(LocatedSegment(child, depth + 1))
        }
    }
    // if the result is eventually invalid, this is the leaf-first list for deletion
    val obsolete = reachable.values
        .sortedByDescending { it.depth }
        .map { it.segment.status }

    // a chain must be the only thing rooted at the original: a second root
    // means a leftover chain, which invalidates the whole set
    val chain = if (roots.size == 1) validChainOrNull(roots.single(), childrenByParent) else null

    return if (chain != null) {
        TranslationChainState.Translated(chain)
    } else {
        TranslationChainState.NeedsTranslation(
            reason = TranslationChainState.Reason.INVALID,
            obsolete = obsolete,
        )
    }
}

/**
 * A footer post linked to its parent, local to [findTranslationChainState].
 * */
private class ChainSegment(
    val status: Status,
    val parentId: String,
    val footer: Footer,
)

/**
 * A [ChainSegment] with its depth from the component root (root = 1),
 * ordering the leaf-first deletion list.
 * */
private data class LocatedSegment(
    val segment: ChainSegment,
    val depth: Int,
)

/**
 * Walk the chain of [root], or null when it is not a valid chain: a simple
 * path (no branch, no repeated id) whose footers are consecutive from
 * `seq` 1, share one hash and one total, and count exactly `total`
 * segments. The chain contract is documented on [findTranslationChainState].
 * */
private fun validChainOrNull(
    root: ChainSegment,
    childrenByParent: Map<String, List<ChainSegment>>,
): TranslationChain? {
    val path = mutableListOf<ChainSegment>()
    val visited = hashSetOf<String>()
    var current = root
    // build the path. there should only be 1 correct path
    while (true) {
        // a repeated id closes a cycle
        if (!visited.add(current.status.id)) return null
        path.add(current)
        val children = childrenByParent[current.status.id].orEmpty()
        if (children.isEmpty()) break
        // more than one child forks the chain
        if (children.size > 1) return null
        current = children.single()
    }
    // check footer
    val hash = root.footer.hash
    val total = root.footer.total
    for ((index, segment) in path.withIndex()) {
        if (segment.footer.seq != index + 1) return null
        if (segment.footer.hash != hash || segment.footer.total != total) return null
    }
    if (path.size != total) return null

    return TranslationChain(path.map { it.status to it.footer })
}
