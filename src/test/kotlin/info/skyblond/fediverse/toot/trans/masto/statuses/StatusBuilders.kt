package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.masto.client.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import java.time.Instant

/**
 * Shared [Status] builders for the status-related tests, pinned to fixed
 * times around [now] so time-dependent logic (cooldown, sort order) stays
 * deterministic. [original] is a Chinese owner toot ("P" by default);
 * [translation] is an English reply of the original carrying a [Footer].
 * The author is [owner]; pool/client test wiring lives in `filter`'s
 * `FilterTestFixture`.
 * */
internal val owner = CredentialAccount("owner", "test-owner")
internal val now: Instant = Instant.parse("2026-10-16T12:00:00Z")

internal fun original(
    id: String = "P",
    parent: String? = null,
    parentAccount: String? = if (parent == null) null else owner.id,
): Status = Status(
    id = id, createdAt = now.minusSeconds(600), content = "<p>中文 body $id</p>",
    inReplyToId = parent, inReplyToAccountId = parentAccount, language = "zh",
    visibility = "public", sensitive = false, mediaAttachments = emptyList(),
)

internal fun translation(
    id: String,
    parent: String = "P",
    seq: Int = 1,
    total: Int = 1,
    hash: String = original().crc32(),
    language: String = "en",
): Status = original(id, parent).copy(
    createdAt = now.minusSeconds(300), language = language,
    content = "<p>Translated<br>${Footer(hash, seq, total)}</p>",
)
