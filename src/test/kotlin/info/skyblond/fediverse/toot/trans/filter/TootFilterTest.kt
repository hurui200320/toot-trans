package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class TootFilterTest {
    @Test
    fun `non-target and null languages are eligible while footer posts and target language skip`() = runTest {
        val statuses = listOf(
            original("null").copy(language = null, content = "<p>Bonjour</p>"),
            original("fr").copy(language = "fr", content = "<p>Bonjour</p>"),
            original("en").copy(language = "en"),
            translation("ja", language = "ja"), translation("en-footer"),
        )
        val f = FilterTestFixture(this, statuses)
        assertEquals(listOf("null", "fr"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `empty decoded HTML and CW skip but CW-only text is eligible`() = runTest {
        val statuses = listOf(
            original("empty").copy(content = null),
            original("blank").copy(content = "<p>&nbsp;<br> </p>", spoilerText = "\n "),
            original("cw").copy(content = "<p><br></p>", spoilerText = "警告"),
        )
        val f = FilterTestFixture(this, statuses)
        assertEquals(listOf(statuses.last()), f.filter())
    }

    @Test
    fun `cooldown includes exact boundary edit reset and future timestamps`() = runTest {
        val statuses = listOf(
            original("boundary").copy(createdAt = now.minusSeconds(180)),
            original("young").copy(createdAt = now.minusSeconds(179)),
            original("edited").copy(editedAt = now.minusSeconds(1)),
            original("future").copy(createdAt = now.plusSeconds(1)),
        )
        val f = FilterTestFixture(this, statuses)
        assertEquals(listOf("boundary"), f.filter().map { it.id })
        assertEquals(listOf("boundary", "young", "edited"), f.filter(cooldown = Duration.ZERO).map { it.id })
    }

    @Test
    fun `pending originals are sorted by effective time oldest first`() = runTest {
        val statuses = listOf(
            original("edited").copy(editedAt = now.minusSeconds(200)),
            original("old").copy(createdAt = now.minusSeconds(700)),
            original("middle").copy(createdAt = now.minusSeconds(500)),
        )
        assertEquals(listOf("old", "middle", "edited"), FilterTestFixture(this, statuses).filter().map { it.id })
    }

    @Test
    fun `any marked target-language direct reply skips original regardless of completeness or hash`() = runTest {
        for (reply in listOf(
            translation("single"), translation("partial", total = 3),
            translation("stale", hash = "AAAAAAAA"), translation("out-of-order", seq = 2, total = 3),
        )) {
            val statuses = listOf(original(), reply)
            val f = FilterTestFixture(this, statuses)
            assertTrue(f.filter().isEmpty(), reply.id)
            assertEquals(statuses.associateBy { it.id }, f.remote)
            assertTrue(f.lookups.isEmpty())
        }
    }

    @Test
    fun `duplicate or forked translations just skip without graph reconstruction`() = runTest {
        val statuses = listOf(
            original(), translation("T1", total = 2), translation("T2", "T1", 2, 2),
            translation("fork", "T1", 2, 2), translation("duplicate"),
        )
        val f = FilterTestFixture(this, statuses)
        assertTrue(f.filter().isEmpty())
        assertEquals(statuses.associateBy { it.id }, f.remote)
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `edits after translation do not trigger a replacement`() = runTest {
        for (edited in listOf(original().copy(content = "<p>edited</p>"), original().copy(spoilerText = "CW"))) {
            val statuses = listOf(edited, translation("T"))
            val f = FilterTestFixture(this, statuses)
            assertTrue(f.filter().isEmpty())
            assertEquals(statuses.associateBy { it.id }, f.remote)
        }
    }

    @Test
    fun `other-language marked reply and ordinary target-language reply do not suppress original`() = runTest {
        val p = original()
        val replies = listOf(
            translation("ja", language = "ja"), original("comment", "P").copy(language = "en"),
            translation("invalid-footer").copy(content = "<p>Hello<br>🌐AAAAAAAA#0#1</p>"),
        )
        for (reply in replies) {
            val f = FilterTestFixture(this, listOf(p, reply))
            assertEquals(listOf(p), f.filter())
            assertTrue(f.lookups.isEmpty())
        }
    }

    @Test
    fun `translation for another original does not suppress pending original`() = runTest {
        val p = original()
        val q = original("Q")
        val f = FilterTestFixture(this, listOf(p, q, translation("T", parent = "Q")))
        assertEquals(listOf(p), f.filter())
    }

    @Test
    fun `self ancestry qualifies but external missing inconsistent and cyclic ancestry skip`() = runTest {
        val root = original("root")
        val self = original("self", "root")
        val external = original("external", "other", "other")
        val nestedExternal = original("nested", "external")
        val missing = original("missing", "gone")
        val inconsistent = original("inconsistent", "root", null)
        val cycle = original("cycle", "cycle")
        val f = FilterTestFixture(this, listOf(root, self, external, nestedExternal, missing, inconsistent, cycle))
        assertEquals(listOf(root, self), f.filter())
        assertEquals(listOf(setOf("gone")), f.lookups)
    }

    @Test
    fun `existing translation skips without even fetching ancestry`() = runTest {
        val p = original(parent = "not-in-scan")
        val f = FilterTestFixture(this, listOf(p, translation("T", total = 3)))
        f.fetch = { error("No lookup needed for already translated originals") }
        assertTrue(f.filter().isEmpty())
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `ancestry failures propagate instead of treating original as eligible`() = runTest {
        val p = original(parent = "not-in-scan")
        val f = FilterTestFixture(this, listOf(p))
        f.fetch = { throw IOException("offline") }
        val transport = assertFailsWith<IOException> { f.filter() }
        assertEquals("offline", transport.message)
        f.fetch = { throw MastoApiException(401, "bad token") }
        val api = assertFailsWith<MastoApiException> { f.filter() }
        assertEquals(401, api.httpCode)
        f.fetch = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { f.filter() }
        assertEquals(3, f.lookups.size)
    }

    @Test
    fun `empty scan returns no pending originals`() = runTest {
        val f = FilterTestFixture(this, emptyList())
        assertTrue(f.filter().isEmpty())
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `invalid filter settings fail fast`() = runTest {
        val f = FilterTestFixture(this, emptyList())
        for (cooldown in listOf((-1).minutes, Duration.INFINITE)) {
            val error = assertFailsWith<IllegalArgumentException> { TootFilter(f.pool, "en", cooldown) }
            assertTrue(error.message!!.contains("translationCooldown"))
        }
        val error = assertFailsWith<IllegalArgumentException> { TootFilter(f.pool, " ") }
        assertTrue(error.message!!.contains("targetLangCode"))
    }
}
