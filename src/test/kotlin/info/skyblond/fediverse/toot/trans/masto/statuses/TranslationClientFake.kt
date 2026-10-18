package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.client.statuses.CreateStatusRequest
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * Statuses client fake for the translation-stage tests
 * ([ReplyChainContext], `TranslationProcessor`):
 *
 * - reads resolve from [remote] (so `StatusesCachePool` works against
 *   it) and record their id sets in [lookups];
 * - sources resolve from [sources] first and otherwise fall back to
 *   the status's content as plain text, see [plainText]; every request
 *   is recorded in [sourceRequests];
 * - creates record the draft in [createRequests], then answer through
 *   the swappable [create] handler (default: [generatedSegment]);
 * - deletes record the id in [deleteRequests], run the swappable
 *   [delete] behavior, then remove the status from [remote].
 *
 * [fetch], [source] and [create] are swappable failure/success scripts,
 * matching the fixture style of the other status tests.
 * */
internal class TranslationClientFake(statuses: Iterable<Status> = emptyList()) {
    val remote = LinkedHashMap<String, Status>().apply {
        statuses.forEach { put(it.id, it) }
    }

    val lookups = mutableListOf<Set<String>>()
    val sourceRequests = mutableListOf<String>()
    val createRequests = mutableListOf<CreateStatusRequest>()
    val deleteRequests = mutableListOf<String>()

    /** Explicit source overrides; consulted before the content fallback. */
    val sources = mutableMapOf<String, StatusSource>()

    /** Replaces the read path entirely when set. */
    var fetch: (suspend (Set<String>) -> List<FetchedStatus>)? = null

    var source: suspend (String) -> StatusSource = { id ->
        sources[id] ?: StatusSource(
            id = id,
            text = plainText(remote.getValue(id).content.orEmpty()),
            spoilerText = remote.getValue(id).spoilerText.orEmpty(),
        )
    }

    /** Answer for one create; the default is [generatedSegment]. */
    var create: suspend (CreateStatusRequest) -> Status = { draft -> generatedSegment(draft) }

    /** Runs after a delete is recorded, before the status is removed. */
    var delete: suspend (String) -> Unit = { }

    private var nextSegmentId = 1

    val client = object : StatusesClient {
        override suspend fun getStatuses(statusIds: Set<String>): List<FetchedStatus> {
            lookups += statusIds.toSet()
            fetch?.let { return it(statusIds) }
            return statusIds.mapNotNull(remote::get).map { fetched(it) }
        }

        override suspend fun getStatus(statusId: String): FetchedStatus = error("Use the pool")

        override suspend fun getStatusSource(statusId: String): StatusSource {
            sourceRequests += statusId
            return source(statusId)
        }

        override suspend fun createStatus(draft: CreateStatusRequest): Status {
            createRequests += draft
            val status = create(draft)
            remote[status.id] = status
            return status
        }

        override suspend fun deleteStatus(statusId: String) {
            deleteRequests += statusId
            delete(statusId)
            remote.remove(statusId)
        }
    }

    /**
     * A plausible created translation segment for [draft]: a fresh
     * "created-N" id, the drafted text as content (`\n` rendered as
     * `<br>`, so [plainText] round-trips it), the drafted reply link,
     * language and visibility.
     * */
    fun generatedSegment(draft: CreateStatusRequest): Status = Status(
        id = "created-${nextSegmentId++}",
        createdAt = now.plusSeconds(60),
        content = "<p>${draft.status.replace("\n", "<br>")}</p>",
        spoilerText = draft.spoilerText,
        inReplyToId = draft.inReplyToId,
        inReplyToAccountId = owner.id,
        language = draft.language,
        visibility = draft.visibility ?: "unlisted",
        sensitive = draft.sensitive ?: false,
        mediaAttachments = emptyList(),
    )

    companion object {
        /**
         * Content HTML as composing-like plain text: `<br>` becomes a
         * newline, paragraphs are newline-separated, and the surrounding
         * blank lines are trimmed. Used to keep generated segments'
         * sources faithful enough for footer stripping.
         * */
        fun plainText(html: String): String {
            val body = Jsoup.parseBodyFragment(html).body()
            // <br> is empty, so wholeText() would drop it and glue the
            // footer to the body line; swap it for a text newline first
            body.select("br").forEach { it.replaceWith(TextNode("\n")) }
            body.select("p").forEach { paragraph ->
                paragraph.before(TextNode("\n"))
                paragraph.after(TextNode("\n"))
            }
            return body.wholeText().trim('\n')
        }
    }
}
