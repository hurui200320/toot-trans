package info.skyblond.fediverse.toot.trans.filter

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import info.skyblond.fediverse.toot.trans.masto.statuses.now
import info.skyblond.fediverse.toot.trans.masto.statuses.original
import info.skyblond.fediverse.toot.trans.masto.statuses.translation
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
    fun `eligible originals are sorted by effective time oldest first`() = runTest {
        val statuses = listOf(
            original("edited").copy(editedAt = now.minusSeconds(200)),
            original("old").copy(createdAt = now.minusSeconds(700)),
            original("middle").copy(createdAt = now.minusSeconds(500)),
        )
        assertEquals(listOf("old", "middle", "edited"), FilterTestFixture(this, statuses).filter().map { it.id })
    }

    @Test
    fun `footer replies never suppress the original because translation state is out of scope`() = runTest {
        for (reply in listOf(
            translation("single"), translation("partial", total = 3),
            translation("stale", hash = "AAAAAAAA"), translation("out-of-order", seq = 2, total = 3),
        )) {
            val statuses = listOf(original(), reply)
            val f = FilterTestFixture(this, statuses)
            assertEquals(listOf("P"), f.filter().map { it.id }, reply.id)
            assertEquals(statuses.associateBy { it.id }, f.remote)
            assertTrue(f.lookups.isEmpty())
        }
    }

    @Test
    fun `duplicate or forked translations leave the original eligible`() = runTest {
        val statuses = listOf(
            original(), translation("T1", total = 2), translation("T2", "T1", 2, 2),
            translation("fork", "T1", 2, 2), translation("duplicate"),
        )
        val f = FilterTestFixture(this, statuses)
        assertEquals(listOf("P"), f.filter().map { it.id })
        assertEquals(statuses.associateBy { it.id }, f.remote)
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `edits after translation do not change filter eligibility`() = runTest {
        for (edited in listOf(original().copy(content = "<p>edited</p>"), original().copy(spoilerText = "CW"))) {
            val statuses = listOf(edited, translation("T"))
            val f = FilterTestFixture(this, statuses)
            assertEquals(listOf("P"), f.filter().map { it.id })
            assertEquals(statuses.associateBy { it.id }, f.remote)
            assertTrue(f.lookups.isEmpty())
        }
    }

    @Test
    fun `other-language and target-language replies leave the original eligible`() = runTest {
        val p = original()
        val replies = listOf(
            translation("ja", language = "ja"),
            original("comment", "P").copy(language = "en"),
        )
        for (reply in replies) {
            val f = FilterTestFixture(this, listOf(p, reply))
            assertEquals(listOf(p), f.filter())
            assertTrue(f.lookups.isEmpty())
        }
    }

    @Test
    fun `malformed footer text is an ordinary reply and stays eligible`() = runTest {
        val p = original()
        val reply = translation("invalid-footer").copy(
            content = "<p>Hello<br>🌐AAAAAAAA#0#1</p>",
            language = "zh",
        )
        // the reply waits for p's translation, so seed one
        val parentTranslation = translation("T-P", parent = "P")
        val f = FilterTestFixture(this, listOf(p, reply, parentTranslation))
        assertEquals(listOf(p, reply), f.filter())
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a translation for another original leaves both originals eligible`() = runTest {
        val p = original()
        val q = original("Q")
        val f = FilterTestFixture(this, listOf(p, q, translation("T", parent = "Q")))
        assertEquals(listOf(p, q), f.filter())
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
        // self waits for the translated-ancestry prerequisite, so root
        // must carry a translation for this test to see self
        val rootTranslation = translation("T-root", parent = "root")
        val f = FilterTestFixture(
            this,
            listOf(root, self, external, nestedExternal, missing, inconsistent, cycle, rootTranslation),
        )
        assertEquals(listOf(root, self), f.filter())
        assertEquals(listOf(setOf("gone")), f.lookups)
    }

    @Test
    fun `an untranslated in-scan ancestor defers its descendants`() = runTest {
        val statuses = listOf(
            original("A"),
            original("B", parent = "A"),
            original("C", parent = "B"),
        )
        val f = FilterTestFixture(this, statuses)
        // B waits for A's translation, C for the whole visible chain
        assertEquals(listOf("A"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a translated ancestor unblocks the next generation`() = runTest {
        val statuses = listOf(
            original("A"),
            translation("TA", parent = "A"),
            original("B", parent = "A"),
            original("C", parent = "B"),
        )
        val f = FilterTestFixture(this, statuses)
        assertEquals(listOf("A", "B"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `an edited ancestor resetting its cooldown keeps descendants deferred`() = runTest {
        val statuses = listOf(
            original("A"),
            original("B", parent = "A").copy(editedAt = now.minusSeconds(1)),
            original("C", parent = "B"),
        )
        val f = FilterTestFixture(this, statuses)
        // B is still cooling down, so C has no B translation to build on
        assertEquals(listOf("A"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a stale complete ancestor chain still settles the prerequisite`() = runTest {
        val statuses = listOf(
            original("A"),
            translation("TA", parent = "A", hash = "AAAAAAAA"),
            original("B", parent = "A"),
        )
        val f = FilterTestFixture(this, statuses)
        assertEquals(listOf("A", "B"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `ancestors that need no translation never block their replies`() = runTest {
        val cases = mapOf(
            "target language" to original("A-en").copy(language = "en"),
            "blank body" to original("A-blank").copy(content = "<p><br></p>", spoilerText = null),
            "footer post" to translation("A-tr").copy(inReplyToId = null, inReplyToAccountId = null),
        )
        cases.forEach { (case, ancestor) ->
            val child = original("child", parent = ancestor.id)
            val f = FilterTestFixture(this, listOf(ancestor, child))
            assertEquals(listOf("child"), f.filter().map { it.id }, case)
            assertTrue(f.lookups.isEmpty(), case)
        }
    }

    @Test
    fun `a reply to a translation segment walks past it to the original`() = runTest {
        val statuses = listOf(
            original("A"),
            translation("TA", parent = "A"),
            original("child", parent = "TA"),
        )
        val f = FilterTestFixture(this, statuses)
        // the footer parent needs no translation, but the walk must
        // continue to TA's parent A and require its chain
        assertEquals(listOf("A", "child"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `ancestors outside the scan are assumed settled`() = runTest {
        val outside = original("outside")
        val child = original("child", parent = "outside")
        val f = FilterTestFixture(this, listOf(child, outside))
        // ancestry still resolves the parent through the pool, but the
        // prerequisite walks only the scan
        assertEquals(listOf("child"), f.filter(statuses = listOf(child)).map { it.id })
        assertEquals(listOf(setOf("outside")), f.lookups)
    }

    @Test
    fun `an invalid ancestor chain blocks its descendants`() = runTest {
        val statuses = listOf(
            original("A"),
            translation("TA1", parent = "A", seq = 1, total = 2),
            original("B", parent = "A"),
        )
        val f = FilterTestFixture(this, statuses)
        // A's half-posted chain does not count as translated
        assertEquals(listOf("A"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a gap above a translated ancestor still blocks descendants`() = runTest {
        val statuses = listOf(
            original("X"),
            original("A", parent = "X"),
            translation("TA", parent = "A"),
            original("B", parent = "A"),
        )
        val f = FilterTestFixture(this, statuses)
        // A is translated but its own parent X is not: B waits for the
        // full visible chain, not just the immediate parent
        assertEquals(listOf("X"), f.filter().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `footer replies do not shortcut the ancestry check`() = runTest {
        val p = original(parent = "not-in-scan")
        val f = FilterTestFixture(this, listOf(p, translation("T", total = 3)))
        // the missing ancestor fails checkReplyChain, so the original is not eligible
        assertTrue(f.filter().isEmpty())
        assertEquals(listOf(setOf("not-in-scan")), f.lookups)
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
    fun `empty scan returns no eligible originals`() = runTest {
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
