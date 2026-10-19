package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.MastoApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * The entrance filter ([PendingQueue.feed]), the work window mechanics
 * (order, replace, drop, cursor) and the reply-chain qualification it
 * carries. The chain classification itself is covered by
 * [TranslationChainTest]; here only the queue's use of it.
 * */
class PendingQueueTest {
    @Test
    fun `non-target and null languages are eligible while target language skips`() = runTest {
        val statuses = listOf(
            original("null").copy(language = null, content = "<p>Bonjour</p>"),
            original("fr").copy(language = "fr", content = "<p>Bonjour</p>"),
            original("en").copy(language = "en"),
        )
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        // equal createdAt ties break by id, so fr sorts before null
        assertEquals(listOf("fr", "null"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `target language footer posts are kept as chain material while other languages skip`() = runTest {
        val statuses = listOf(original(), translation("T"), translation("ja", language = "ja"))
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        assertFalse(f.queue.isTranslation("P"))
        assertTrue(f.queue.isTranslation("T"))
        assertEquals(listOf("P", "T"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `isTranslation fails fast for a status that is not queued`() = runTest {
        val f = PendingQueueFixture(this, listOf(original(), translation("T")))
        f.feed()
        assertTrue(f.queue.isTranslation("T"))
        // an id that left the window (dropped here) is not answerable either
        f.queue.drop("T")
        val dropped = assertFailsWith<NoSuchElementException> { f.queue.isTranslation("T") }
        assertTrue(dropped.message!!.contains("T"))
        val missing = assertFailsWith<NoSuchElementException> { f.queue.isTranslation("missing") }
        assertTrue(missing.message!!.contains("missing"))
    }

    @Test
    fun `empty decoded HTML and CW skip but CW-only text is eligible`() = runTest {
        val statuses = listOf(
            original("empty").copy(content = null),
            original("blank").copy(content = "<p>&nbsp;<br> </p>", spoilerText = "\n "),
            original("cw").copy(content = "<p><br></p>", spoilerText = "警告"),
        )
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        assertEquals(listOf("cw"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `originals older than the cutoff at fetch time are skipped while younger ones are queued`() = runTest {
        // boundary: exactly at the cutoff passes, one second older does not
        val statuses = listOf(
            original("too-old").copy(createdAt = now.minusSeconds(601)),
            original("boundary").copy(createdAt = now.minusSeconds(600)),
            original("young").copy(createdAt = now.minusSeconds(300)),
        )
        val f = PendingQueueFixture(this, statuses, maxAge = 10.minutes)
        f.feed()
        assertEquals(listOf("boundary", "young"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `an edit resets the age to the edit time`() = runTest {
        val justEdited = original().copy(createdAt = now.minusSeconds(7200), editedAt = now.minusSeconds(300))
        val staleEdit = original("old").copy(createdAt = now.minusSeconds(7200), editedAt = now.minusSeconds(601))
        val f = PendingQueueFixture(this, listOf(justEdited, staleEdit), maxAge = 10.minutes)
        f.feed()
        // the first edit counts as current, the second one is already past the cutoff
        assertEquals(listOf("P"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a future creation time counts as young`() = runTest {
        val future = original().copy(createdAt = now.plusSeconds(60))
        val f = PendingQueueFixture(this, listOf(future), maxAge = 10.minutes)
        f.feed()
        assertEquals(listOf("P"), f.drain().map { it.id })
    }

    @Test
    fun `a zero max age disables the cutoff`() = runTest {
        val ancient = original().copy(createdAt = now.minusSeconds(86400 * 30))
        val f = PendingQueueFixture(this, listOf(ancient), maxAge = Duration.ZERO)
        f.feed()
        assertEquals(listOf("P"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `the cutoff never applies to target language segments`() = runTest {
        // the segment is older than the cutoff but must stay chain material
        val segment = translation("T", parent = "P").copy(createdAt = now.minusSeconds(650))
        val f = PendingQueueFixture(this, listOf(original("P"), segment), maxAge = 10.minutes)
        f.feed()
        assertTrue(f.queue.isTranslation("T"))
        assertEquals(listOf("T", "P"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `an old original stays as the chain context of a young reply without a lookup`() = runTest {
        val root = original("root").copy(createdAt = now.minusSeconds(900))
        val reply = original("P", parent = "root")
        val f = PendingQueueFixture(this, listOf(root, reply), maxAge = 10.minutes)
        f.feed()
        // the old root is skipped as work but still seeds the pool, so the
        // reply's ancestry qualification resolves locally
        assertEquals(listOf("P"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a queued original that ages past the cutoff on re-feed leaves the window and records the cursor`() = runTest {
        val f = PendingQueueFixture(this, listOf(original()), maxAge = 10.minutes)
        f.feed()
        assertTrue(f.queue.hasNext())
        // the same version observed one minute later is past the cutoff:
        // rejected at feed, and being the head it records the cursor
        f.feed(fetchedAt = now.plusSeconds(60))
        assertFalse(f.queue.hasNext())
        assertEquals("P", f.queue.cursor())
    }

    @Test
    fun `malformed footer text is an ordinary reply and stays eligible`() = runTest {
        val p = original()
        val reply = translation("invalid-footer").copy(
            content = "<p>Hello<br>🌐AAAAAAAA#0#1</p>",
            language = "zh",
        )
        val f = PendingQueueFixture(this, listOf(p, reply))
        f.feed()
        assertEquals(listOf("P", "invalid-footer"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `a translation of one original does not affect another original`() = runTest {
        val p = original()
        val q = original("Q")
        val f = PendingQueueFixture(this, listOf(p, q, translation("T", parent = "Q")))
        f.feed()
        assertEquals(listOf("P", "Q", "T"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `other language footers and target language comments are not queued`() = runTest {
        val p = original()
        val replies = listOf(
            translation("ja", language = "ja"),
            original("comment", "P").copy(language = "en"),
        )
        for (reply in replies) {
            val f = PendingQueueFixture(this, listOf(p, reply))
            f.feed()
            assertEquals(listOf("P"), f.drain().map { it.id }, reply.id)
            assertTrue(f.lookups.isEmpty(), reply.id)
        }
    }

    @Test
    fun `root and full self ancestry qualify using seeded pool without calls`() = runTest {
        val statuses = listOf(original("root"), original("middle", "root"), original("P", "middle"))
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        assertEquals(listOf("P", "middle", "root"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `ancestors beyond the feed are checked through the pool`() = runTest {
        val root = original("root")
        val parent = original("parent", "root", "other")
        val p = original(parent = "parent")
        val f = PendingQueueFixture(this, listOf(root, parent, p))
        f.feed(listOf(p))
        assertTrue(f.drain().isEmpty())
        assertEquals(listOf(setOf("parent")), f.lookups)
    }

    @Test
    fun `missing ancestors fail closed`() = runTest {
        val p = original(parent = "missing")
        val f = PendingQueueFixture(this, listOf(p))
        f.feed(listOf(p))
        assertTrue(f.drain().isEmpty())
        assertEquals(listOf(setOf("missing")), f.lookups)
    }

    @Test
    fun `inconsistent metadata fails without lookup`() = runTest {
        val f = PendingQueueFixture(this, emptyList())
        f.feed(
            listOf(
                original(parent = "parent", parentAccount = null),
                original(parentAccount = owner.id),
                original(parent = ""),
            )
        )
        assertTrue(f.drain().isEmpty())
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `self loop and longer cycle are detected without duplicate reads`() = runTest {
        val p = original(parent = "P")
        val f = PendingQueueFixture(this, listOf(p))
        f.feed()
        assertTrue(f.drain().isEmpty())
        assertTrue(f.lookups.isEmpty())

        val a = original("a", "b")
        val b = original("b", "a")
        val g = PendingQueueFixture(this, listOf(a, b))
        g.feed(listOf(a))
        assertTrue(g.drain().isEmpty())
        assertEquals(listOf(setOf("b")), g.lookups)
        // the pool now caches b, so feeding it cannot trigger more reads
        g.feed(listOf(b))
        assertTrue(g.drain().isEmpty())
        assertEquals(listOf(setOf("b")), g.lookups)
    }

    @Test
    fun `transport API and cancellation failures propagate`() = runTest {
        val p = original(parent = "parent")
        val f = PendingQueueFixture(this, listOf(p))
        f.fetch = { throw IOException("offline") }
        val transport = assertFailsWith<IOException> { f.feed(listOf(p)) }
        assertEquals("offline", transport.message)
        f.fetch = { throw MastoApiException(401, "bad token") }
        val api = assertFailsWith<MastoApiException> { f.feed(listOf(p)) }
        assertEquals(401, api.httpCode)
        f.fetch = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { f.feed(listOf(p)) }
        assertEquals(3, f.lookups.size)
    }

    @Test
    fun `footer replies do not shortcut the ancestry check`() = runTest {
        val p = original(parent = "not-in-scan")
        val f = PendingQueueFixture(this, listOf(p, translation("T", total = 3)))
        f.feed()
        // the missing ancestor fails the reply-chain check, so the original
        // is not queued; the translation remains chain material
        assertEquals(listOf("T"), f.drain().map { it.id })
        assertEquals(listOf(setOf("not-in-scan")), f.lookups)
    }

    @Test
    fun `entries are ordered by creation time and edits do not reorder them`() = runTest {
        val statuses = listOf(
            original("edited").copy(editedAt = now.minusSeconds(200)),
            original("old").copy(createdAt = now.minusSeconds(700)),
            original("middle").copy(createdAt = now.minusSeconds(500)),
        )
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        // created: old (-700), edited (-600), middle (-500); the edit time
        // deliberately does not move "edited" to the end
        assertEquals(listOf("old", "edited", "middle"), f.drain().map { it.id })
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `equal creation times break ties by id`() = runTest {
        val statuses = listOf(original("b"), original("a"), original("c"))
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        assertEquals(listOf("a", "b", "c"), f.drain().map { it.id })
    }

    @Test
    fun `re-feeding an id replaces the queued copy`() = runTest {
        val f = PendingQueueFixture(this, listOf(original()))
        f.feed()
        f.feed(listOf(original().copy(content = "<p>updated body</p>")))
        val drained = f.drain()
        assertEquals(1, drained.size)
        assertEquals("<p>updated body</p>", drained.single().content)
    }

    @Test
    fun `a re-fed status that no longer qualifies leaves the window`() = runTest {
        // the queued head is edited into the target language: the skip
        // drops it and, being the head, records the cursor
        val p = original()
        val head = PendingQueueFixture(this, listOf(p))
        head.feed()
        head.remote[p.id] = p.copy(language = "en")
        head.feed()
        assertEquals("P", head.queue.cursor())
        assertTrue(head.drain().isEmpty())

        // a queued non-head is edited out: the skip drops only it, and the
        // accepted re-feed of the head must not move the cursor either
        val q = original("Q")
        val nonHead = PendingQueueFixture(this, listOf(original(), q))
        nonHead.feed()
        nonHead.remote[q.id] = q.copy(language = "en")
        nonHead.feed()
        assertNull(nonHead.queue.cursor())
        assertEquals(listOf("P"), nonHead.drain().map { it.id })
    }

    @Test
    fun `dropping the head records the cursor and dropping elsewhere does not`() = runTest {
        val statuses = listOf(original("A"), original("B"), original("C"), original("D"))
        val f = PendingQueueFixture(this, statuses)
        f.feed()
        assertNull(f.queue.cursor())
        // D is not the head: no cursor, the entry is just removed
        f.queue.drop("D")
        assertNull(f.queue.cursor())
        f.queue.drop("A")
        assertEquals("A", f.queue.cursor())
        // C is not the head (B is), so its drop leaves the cursor where it was
        f.queue.drop("C")
        assertEquals("A", f.queue.cursor())
        f.queue.drop("B")
        assertEquals("B", f.queue.cursor())
        assertTrue(f.drain().isEmpty())
    }

    @Test
    fun `draining the window advances the cursor to the last head`() = runTest {
        val f = PendingQueueFixture(this, listOf(original("A"), original("B")))
        f.feed()
        assertEquals(listOf("A", "B"), f.drain().map { it.id })
        assertEquals("B", f.queue.cursor())
        assertFalse(f.queue.hasNext())
    }

    @Test
    fun `dropping an id that is not queued is a no-op even when empty`() = runTest {
        val f = PendingQueueFixture(this, emptyList())
        f.feed(emptyList())
        f.queue.drop("missing")
        assertNull(f.queue.cursor())
        assertFalse(f.queue.hasNext())
    }

    @Test
    fun `an empty feed leaves the window empty`() = runTest {
        val f = PendingQueueFixture(this, emptyList())
        f.feed(emptyList())
        assertFalse(f.queue.hasNext())
        assertNull(f.queue.cursor())
        assertTrue(f.lookups.isEmpty())
    }

    @Test
    fun `chain state is classified from the window merged with the pool snapshot`() = runTest {
        val p = original()
        val t = translation("T")
        val f = PendingQueueFixture(this, listOf(p, t))
        f.feed()
        val translated = assertIs<TranslationChainState.Translated>(f.queue.findTranslationChainState(p))
        assertEquals(listOf("T"), translated.chain.segments.map { it.first.id })

        // dropping the segment from the window does not hide it: the drop
        // is not a deletion, and the pool still holds the observation
        f.queue.drop("T")
        assertIs<TranslationChainState.Translated>(f.queue.findTranslationChainState(p))

        // once the pool forgets it too (the deletion path), the segment is
        // invisible to the classification
        f.pool.invalidate("T")
        val needs = assertIs<TranslationChainState.NeedsTranslation>(f.queue.findTranslationChainState(p))
        assertEquals(TranslationChainState.Reason.ABSENT, needs.reason)
    }

    @Test
    fun `pool-only statuses are chain material`() = runTest {
        val p = original()
        val f = PendingQueueFixture(this, listOf(p))
        f.feed()
        // never fed into the queue, only observed by the pool
        f.pool.seed(listOf(fetched(translation("T"))))
        val translated = assertIs<TranslationChainState.Translated>(f.queue.findTranslationChainState(p))
        assertEquals(listOf("T"), translated.chain.segments.map { it.first.id })
    }

    @Test
    fun `the newest fetch wins when the window and the pool know the same id`() = runTest {
        val p = original()
        val t = translation("T")
        val f = PendingQueueFixture(this, listOf(p, t))
        f.feed()
        assertIs<TranslationChainState.Translated>(f.queue.findTranslationChainState(p))

        // a later observation retags the toot away from the target
        // language: the stale queued copy must not be used
        f.pool.seed(listOf(fetched(t.copy(language = "ja"), at = now.plusSeconds(60))))
        val needs = assertIs<TranslationChainState.NeedsTranslation>(f.queue.findTranslationChainState(p))
        assertEquals(TranslationChainState.Reason.ABSENT, needs.reason)
    }

    @Test
    fun `an exact fetch-time tie keeps the queued copy`() = runTest {
        val p = original()
        val t = translation("T")
        val f = PendingQueueFixture(this, listOf(p, t))
        f.feed()
        // same fetchedAt as the queued copy: the window copy wins the tie
        f.pool.seed(listOf(fetched(t.copy(language = "ja"), at = now)))
        val translated = assertIs<TranslationChainState.Translated>(f.queue.findTranslationChainState(p))
        assertEquals(listOf("T"), translated.chain.segments.map { it.first.id })
    }

    @Test
    fun `blank account id and language code fail fast`() = runTest {
        val f = PendingQueueFixture(this, emptyList())
        val badAccount = assertFailsWith<IllegalArgumentException> { PendingQueue(" ", "en", f.pool) }
        assertTrue(badAccount.message!!.contains("ourAccountId"))
        val badLang = assertFailsWith<IllegalArgumentException> { PendingQueue(owner.id, " ", f.pool) }
        assertTrue(badLang.message!!.contains("targetLangCode"))
    }

    @Test
    fun `negative and infinite max ages fail fast while zero and the default are accepted`() = runTest {
        val f = PendingQueueFixture(this, emptyList())
        for (bad in listOf((-1).minutes, Duration.INFINITE)) {
            val error = assertFailsWith<IllegalArgumentException> { PendingQueue(owner.id, "en", f.pool, bad) }
            assertTrue(error.message!!.contains("maxAge"))
        }
        PendingQueue(owner.id, "en", f.pool, Duration.ZERO)
        assertEquals(1.days, PendingQueue.DEFAULT_MAX_AGE)
    }
}
