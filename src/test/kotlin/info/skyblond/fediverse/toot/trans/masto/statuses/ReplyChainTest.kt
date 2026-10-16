package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.filter.FilterTestFixture
import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyChainTest {
    @Test
    fun `root and full self ancestry qualify using seeded pool without calls`() = runTest {
        val statuses = listOf(original("root"), original("middle", "root"), original("P", "middle"))
        val f = FilterTestFixture(this, statuses)
        f.pool.seed(statuses)
        assertTrue(f.pool.checkReplyChain(statuses.first(), owner))
        assertTrue(f.pool.checkReplyChain(statuses.last(), owner))
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `remote ancestors beyond scan are checked through pool`() = runTest {
        val root = original("root")
        val parent = original("parent", "root", "other")
        val p = original(parent = "parent")
        val f = FilterTestFixture(this, listOf(root, parent, p))
        f.pool.seed(listOf(p))
        assertFalse(f.pool.checkReplyChain(p, owner))
        assertEquals(listOf(setOf("parent")), f.lookups)
    }

    @Test
    fun `missing ancestors fail closed`() = runTest {
        val p = original(parent = "missing")
        val f = FilterTestFixture(this, listOf(p))
        assertFalse(f.pool.checkReplyChain(p, owner))
        assertEquals(listOf(setOf("missing")), f.lookups)
    }

    @Test
    fun `inconsistent metadata fails without lookup`() = runTest {
        val f = FilterTestFixture(this, emptyList())
        for (p in listOf(
            original(parent = "parent", parentAccount = null),
            original(parentAccount = owner.id), original(parent = ""),
        )) {
            assertFalse(f.pool.checkReplyChain(p, owner))
        }
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `self loop and longer cycle are detected without duplicate reads`() = runTest {
        val p = original(parent = "P")
        val f = FilterTestFixture(this, listOf(p))
        assertFalse(f.pool.checkReplyChain(p, owner))
        assertTrue(f.lookups.isEmpty())
        val a = original("a", "b")
        val b = original("b", "a")
        f.remote.putAll(listOf(a, b).associateBy { it.id })
        assertFalse(f.pool.checkReplyChain(a, owner))
        assertEquals(listOf(setOf("b")), f.lookups)
    }

    @Test
    fun `transport API and cancellation failures propagate`() = runTest {
        val p = original(parent = "parent")
        val f = FilterTestFixture(this, listOf(p))
        f.fetch = { throw IOException("offline") }
        val transport = assertFailsWith<IOException> { f.pool.checkReplyChain(p, owner) }
        assertEquals("offline", transport.message)
        f.fetch = { throw MastoApiException(401, "bad token") }
        val api = assertFailsWith<MastoApiException> { f.pool.checkReplyChain(p, owner) }
        assertEquals(401, api.httpCode)
        f.fetch = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { f.pool.checkReplyChain(p, owner) }
        assertEquals(3, f.lookups.size)
    }
}
