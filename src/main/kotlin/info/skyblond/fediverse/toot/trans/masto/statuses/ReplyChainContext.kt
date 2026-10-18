package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.config.requireValidReplyChainDepth
import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.removeFooterFromText
import info.skyblond.fediverse.toot.trans.llm.TranslationContext
import info.skyblond.fediverse.toot.trans.llm.TranslationContextEntry
import info.skyblond.fediverse.toot.trans.llm.client.ContentPart
import info.skyblond.fediverse.toot.trans.masto.client.media.MediaLoader
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

/**
 * Builds the [TranslationContext] of one toot: its reply-chain
 * ancestry, the composing text of every entry, the existing
 * translation of each ancestor, and the images of the toot itself.
 *
 * The ancestor walk reads [pool], so a chain already seen by the scan,
 * the queue feed or a previous step resolves from the cache; a missing
 * entry (deleted outside this application) truncates the chain with a
 * warning instead of failing, because context is best-effort while the
 * translation itself is not. [maxDepth] caps the walk (0 disables the
 * history); `PendingQueue`'s eligibility already proved that ancestors
 * exist and belong to us, so no authorship is re-checked here.
 *
 * Sources come from `GET /statuses/:id/source` for every entry: only
 * statuses owned by the token account reach this class (see
 * [PendingQueue.feed]), and both the composing fidelity and the
 * content-warning fidelity matter for the prompt.
 *
 * An ancestor's translation is resolved through
 * [PendingQueue.findTranslationChainState] (window merged with the
 * pool, so a translation posted earlier in the same run is visible)
 * and only a complete, valid chain contributes; its segments are
 * joined in footer order with the markers stripped
 * ([removeFooterFromText]), and its first segment's spoiler text, when
 * any, rides along as the chain's translated content warning.
 *
 * Images are loaded for the toot under translation only, through
 * [mediaLoader], capped by [MediaLoader.MAX_IMAGES_PER_REQUEST]; one
 * failed download skips that image (logged), never the translation.
 * */
class ReplyChainContext(
    private val queue: PendingQueue,
    private val pool: StatusesCachePool,
    private val statusesClient: StatusesClient,
    private val mediaLoader: MediaLoader,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) {
    init {
        requireValidReplyChainDepth("maxDepth", maxDepth)
    }

    /**
     * Assemble the context for [toot]: ancestors oldest first, the toot
     * itself last, plus its images. Transport and API failures from the
     * source reads propagate; only media downloads degrade.
     * */
    suspend fun build(toot: Status): TranslationContext {
        val chain = buildChain(toot)
        val entries = chain.mapIndexed { index, status ->
            val source = statusesClient.getStatusSource(status.id)
            // the toot's own state is NeedsTranslation by construction,
            // so only ancestors resolve a translation
            val ancestorTranslation =
                if (index == chain.lastIndex) null else resolveTranslation(status)
            TranslationContextEntry(
                status = status,
                source = source,
                translation = ancestorTranslation?.text,
                translationSpoilerText = ancestorTranslation?.spoilerText,
            )
        }
        return TranslationContext(entries, loadImages(toot))
    }

    /**
     * The ancestry of [toot], root first, at most [maxDepth] ancestors
     * above it. A missing ancestor truncates the chain with a warning.
     * */
    private suspend fun buildChain(toot: Status): List<Status> {
        val chain = ArrayDeque<Status>()
        chain.addFirst(toot)
        var current = toot
        var depth = 0
        while (depth < maxDepth) {
            val parentId = current.inReplyToId?.takeIf { it.isNotBlank() } ?: break
            val parent = pool.getStatus(parentId)?.status
            if (parent == null) {
                logger.warn {
                    "Ancestor $parentId of ${toot.id} is unavailable, truncating the reply chain"
                }
                break
            }
            chain.addFirst(parent)
            current = parent
            depth++
        }
        return chain.toList()
    }

    /**
     * The existing translation of [status], joined and footer-stripped,
     * or null when there is no complete chain.
     * */
    private suspend fun resolveTranslation(status: Status): AncestorTranslation? {
        val state = queue.findTranslationChainState(status)
        if (state !is TranslationChainState.Translated) return null
        val sources = state.chain.segments.map { (segment, _) ->
            statusesClient.getStatusSource(segment.id)
        }
        return AncestorTranslation(
            text = sources.joinToString("\n") { it.text.removeFooterFromText() },
            spoilerText = sources.first().spoilerText.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Download the images of [toot]; one failure (any exception other
     * than cancellation) skips that image, see the class KDoc.
     * */
    private suspend fun loadImages(toot: Status): List<ContentPart.ImagePart> {
        val images = mutableListOf<ContentPart.ImagePart>()
        for (attachment in toot.mediaAttachments) {
            if (images.size >= MediaLoader.MAX_IMAGES_PER_REQUEST) break
            if (attachment.type != "image") continue
            val url = attachment.url?.takeIf { it.isNotBlank() } ?: continue
            try {
                val media = mediaLoader.load(url)
                images += ContentPart.image(media.bytes, media.mediaType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Skipping media ${attachment.id} of toot ${toot.id}" }
            }
        }
        return images
    }

    /**
     * One resolved ancestor translation, local to [resolveTranslation].
     * */
    private data class AncestorTranslation(
        val text: String,
        val spoilerText: String?,
    )

    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Default ancestor depth (see `replyChainDepth`); the deploy
         * default reuses this constant so it cannot drift apart from
         * the programmatic/test default.
         * */
        const val DEFAULT_MAX_DEPTH = 3
    }
}
