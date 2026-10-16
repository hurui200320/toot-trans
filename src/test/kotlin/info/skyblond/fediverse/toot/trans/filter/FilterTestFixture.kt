package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.masto.statuses.CreateStatusRequest
import info.skyblond.fediverse.toot.trans.masto.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesClient
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import info.skyblond.fediverse.toot.trans.masto.statuses.now
import info.skyblond.fediverse.toot.trans.masto.statuses.owner
import kotlinx.coroutines.test.TestScope
import java.time.Clock
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
