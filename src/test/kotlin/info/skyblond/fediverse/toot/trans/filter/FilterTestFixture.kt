package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.masto.accounts.CredentialAccount
import info.skyblond.fediverse.toot.trans.masto.statuses.CreateStatusRequest
import info.skyblond.fediverse.toot.trans.masto.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlinx.coroutines.test.TestScope
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

/** Status reads only: any write or source request fails the test. */
internal class FilterTestFixture(scope: TestScope, statuses: List<Status>) {
    val remote = statuses.associateBy { it.id }.toMutableMap()
    val lookups = mutableListOf<Set<String>>()
    var fetch: suspend (Set<String>) -> List<Status> = { ids -> ids.mapNotNull(remote::get) }
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val client = object : StatusesClient {
        override suspend fun getStatuses(statusIds: Set<String>): List<Status> {
            lookups.add(statusIds.toSet())
            return fetch(statusIds)
        }
        override suspend fun deleteStatus(statusId: String) = error("Filter must not delete")
        override suspend fun getStatus(statusId: String): Status = error("Use pool")
        override suspend fun getStatusSource(statusId: String): StatusSource = error("Not this milestone")
        override suspend fun createStatus(draft: CreateStatusRequest): Status = error("Not this milestone")
    }
    val pool = StatusesPool(
        client, scope.backgroundScope, batchWindow = 1.milliseconds,
        timeSource = scope.testScheduler.timeSource,
    )

    suspend fun filter(
        statuses: List<Status> = remote.values.toList(),
        cooldown: Duration = TootFilter.DEFAULT_COOLDOWN,
    ): List<Status> {
        pool.seed(statuses)
        return TootFilter(pool, "en", cooldown, clock).filter(statuses, owner)
    }
}
