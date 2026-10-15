package info.skyblond.fediverse.toot.trans.masto.statuses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A toot, with only the fields this project needs.
 *
 * `text` and `spoiler_text` are deliberately not parsed here: the
 * plain text for translation comes from [StatusesClient.getStatusSource].
 *
 * See: https://docs.joinmastodon.org/entities/Status/
 * */
@Serializable
data class Status(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    @SerialName("in_reply_to_account_id") val inReplyToAccountId: String? = null,
    val language: String? = null,
    val visibility: String,
    val sensitive: Boolean,
    @SerialName("media_attachments") val mediaAttachments: List<MediaAttachment>,
    val quote: Quote? = null,
    val card: PreviewCard? = null,
    val poll: Poll? = null,
)

/**
 * Media attached to a toot: only the type and the
 * (translatable) alt text are kept.
 * */
@Serializable
data class MediaAttachment(
    val id: String,
    val type: String,
    val description: String? = null,
    val url: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    @SerialName("remote_url") val remoteUrl: String? = null,
)

/**
 * Quoted toot info. One model covers both wire shapes: `ShallowQuote`
 * (with `quoted_status_id`) and the full `Quote` (with a `quoted_status`
 * object, only returned when deleting a toot — not parsed).
 * https://docs.joinmastodon.org/entities/Quote
 * */
@Serializable
data class Quote(
    val state: String,
    @SerialName("quoted_status_id") val quotedStatusId: String? = null,
)

/**
 * Link preview card attached to a toot.
 * */
@Serializable
data class PreviewCard(
    val url: String,
    val type: String,
    val title: String? = null,
    val description: String? = null,
)

/**
 * Poll attached to a toot; the vote counters are not kept.
 * */
@Serializable
data class Poll(
    val id: String,
    val multiple: Boolean,
    val options: List<PollOption>,
)

@Serializable
data class PollOption(
    val title: String,
)

/**
 * Plain text source of a toot.
 * Wire docs: see [StatusesClient.getStatusSource].
 * */
@Serializable
data class StatusSource(
    val id: String,
    val text: String,
    @SerialName("spoiler_text") val spoilerText: String,
)

/**
 * A toot about to be posted, i.e. a translation reply. The non-null
 * fields are sent as form parameters; see [StatusesClient.createStatus]
 * for the wire protocol and the meaning of each parameter.
 *
 * [requestId] is the exception: it identifies the logical request, is
 * never sent to the instance, and only feeds into the
 * `Idempotency-Key` derivation there. Pass the original toot's status
 * id here, so that identical replies to different toots do not block
 * each other server-side.
 * */
data class CreateStatusRequest(
    val status: String,
    val requestId: String,
    val inReplyToId: String? = null,
    val language: String? = null,
    val visibility: String? = null,
    val spoilerText: String? = null,
    val sensitive: Boolean? = null,
)
