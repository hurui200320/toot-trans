package info.skyblond.fediverse.toot.trans.masto.client.statuses

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.util.zip.CRC32

/**
 * A [Status] together with the instant its fetch was issued, supplied by
 * the client's injected [java.time.Clock]. The stamp is taken before the
 * request goes out, and the instance generates the response at or after
 * that instant, so a cooldown check against it can prove the version in
 * hand is the settled one; see
 * [info.skyblond.fediverse.toot.trans.masto.statuses.Cooldown].
 * */
data class FetchedStatus(
    val status: Status,
    val fetchedAt: Instant
)

/**
 * A toot, with only the fields this project needs.
 *
 * [content] is rendered HTML; [spoilerText] is the content warning.
 * The composing text for translation comes from [StatusesClient.getStatusSource].
 *
 * See: https://docs.joinmastodon.org/entities/Status/
 * */
@Serializable
data class Status(
    val id: String,
    // dates arrive as RFC3339 strings, see [Rfc3339InstantSerializer]
    @Serializable(with = Rfc3339InstantSerializer::class)
    @SerialName("created_at") val createdAt: Instant,
    val content: String? = null,
    @SerialName("spoiler_text") val spoilerText: String? = null,
    @Serializable(with = Rfc3339InstantSerializer::class)
    @SerialName("edited_at") val editedAt: Instant? = null,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    @SerialName("in_reply_to_account_id") val inReplyToAccountId: String? = null,
    val language: String? = null,
    val visibility: String,
    val sensitive: Boolean,
    @SerialName("media_attachments") val mediaAttachments: List<MediaAttachment>,
    val quote: Quote? = null,
    val card: PreviewCard? = null,
    val poll: Poll? = null,
    val reblog: Status? = null,
) {
    /**
     * Checksum for reply footers: CRC32 over UTF-8 `spoilerText + "|" + content`,
     * with null fields treated as empty, rendered as eight uppercase hex chars.
     * Uses rendered HTML, not `/source` composing text. The entrance filter
     * (`PendingQueue.feed`) only checks footer presence and does not compare
     * this checksum.
     */
    fun crc32(): String {
        val crc = CRC32()
        val textToHash = (spoilerText ?: "") + "|" + (content ?: "")
        crc.update(textToHash.encodeToByteArray())
        return crc.value.toString(16).padStart(8, '0').uppercase()
    }
}

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

/**
 * [Instant] over the wire: Mastodon dates are the “Internet Date/Time
 * Format” defined in RFC3339 section 5.6, e.g. `2022-08-24T22:29:46.493Z`
 * (offsets like `+08:00` are legal too).
 * https://docs.joinmastodon.org/api/datetime-format/#datetime
 *
 * `Instant.parse`/`toString` cover exactly that grammar, so an
 * unparseable date fails the whole decode (fail-fast on a broken
 * instance response) instead of silently nulling the field.
 * */
object Rfc3339InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())
}
