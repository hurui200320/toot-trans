package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.client.statuses.CreateStatusRequest
import info.skyblond.fediverse.toot.trans.masto.client.statuses.FetchedStatus
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusSource
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusesClient
import kotlinx.coroutines.test.TestScope
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Status reads only: any write or source request fails the test.
 * [lookups] records every batch the pool had to fetch from the fake
 * instance, so tests can prove ancestry reads stayed local to the seeded
 * cache.
 * */
internal class PendingQueueFixture(scope: TestScope, statuses: List<Status>) {
    val remote = statuses.associateBy { it.id }.toMutableMap()
    val lookups = mutableListOf<Set<String>>()
    var fetch: suspend (Set<String>) -> List<FetchedStatus> = { ids ->
        ids.mapNotNull(remote::get).map { fetched(it) }
    }
    val client = object : StatusesClient {
        override suspend fun getStatuses(statusIds: Set<String>): List<FetchedStatus> {
            lookups.add(statusIds.toSet())
            return fetch(statusIds)
        }
        override suspend fun deleteStatus(statusId: String) = error("PendingQueue must not delete")
        override suspend fun getStatus(statusId: String): FetchedStatus = error("Use pool")
        override suspend fun getStatusSource(statusId: String): StatusSource = error("Not this milestone")
        override suspend fun createStatus(draft: CreateStatusRequest): Status = error("Not this milestone")
    }
    val pool = StatusesCachePool(
        client, scope.backgroundScope, batchWindow = 1.milliseconds,
        timeSource = scope.testScheduler.timeSource,
    )
    val queue = PendingQueue(owner.id, "en", pool)

    /**
     * Feed [statuses] (all of [remote] by default) as fetched at
     * [fetchedAt].
     * */
    suspend fun feed(
        statuses: List<Status> = remote.values.toList(),
        fetchedAt: Instant = now,
    ) {
        queue.feed(statuses.map { fetched(it, fetchedAt) })
    }

    /**
     * Drain the window in processing order, dropping each entry the way
     * Main does; the returned list is what the queue would have worked on.
     * */
    suspend fun drain(): List<Status> {
        val drained = mutableListOf<Status>()
        while (queue.hasNext()) {
            val status = queue.next().status
            drained.add(status)
            queue.drop(status.id)
        }
        return drained
    }
}
