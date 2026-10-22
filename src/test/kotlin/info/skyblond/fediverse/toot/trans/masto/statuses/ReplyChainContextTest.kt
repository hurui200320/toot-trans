package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.masto.client.media.LoadedMedia
import info.skyblond.fediverse.toot.trans.masto.client.media.MediaLoader
import info.skyblond.fediverse.toot.trans.masto.client.statuses.MediaAttachment
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Quote
import info.skyblond.fediverse.toot.trans.masto.client.statuses.Status
import info.skyblond.fediverse.toot.trans.masto.client.statuses.StatusSource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * [ReplyChainContext]: the ancestor walk, the source and translation
 * resolution, the quoted-toot context and the media rules (target
 * only, capped, degrade on failures).
 * */
class ReplyChainContextTest {
    private class MediaFake : MediaLoader {
        val loaded = mutableListOf<String>()
        var handler: suspend (String) -> LoadedMedia = { LoadedMedia(byteArrayOf(1, 2), "image/png") }

        override suspend fun load(url: String): LoadedMedia {
            loaded += url
            return handler(url)
        }
    }

    private class Fixture(scope: TestScope, statuses: List<Status>, maxDepth: Int = 3) {
        val clientFake = TranslationClientFake(statuses)
        val pool = StatusesCachePool(
            clientFake.client, scope.backgroundScope,
            batchWindow = 1.milliseconds, timeSource = scope.testScheduler.timeSource,
        )
        val queue = PendingQueue(owner.id, "en", pool)
        val media = MediaFake()
        val context = ReplyChainContext(queue, pool, clientFake.client, media, maxDepth)

        suspend fun feed(statuses: List<Status> = clientFake.remote.values.toList()) {
            queue.feed(statuses.map { fetched(it) })
        }
    }

    private fun image(id: String, url: String = "https://media.example/$id.png"): MediaAttachment =
        MediaAttachment(id = id, type = "image", url = url)

    // the wire shape of Status.quote: the state gate plus the embedded
    // quoted toot, see Models.kt
    private fun quote(quoted: Status, state: String = "accepted"): Quote =
        Quote(state = state, quotedStatus = quoted)

    @Test
    fun `depth zero translates the toot without history`() {
        runTest {
            val f = Fixture(
                this,
                listOf(original("root"), original("mid", "root"), original("P", "mid")),
                maxDepth = 0,
            )
            f.feed()
            val context = f.context.build(f.clientFake.remote.getValue("P"))
            assertEquals(listOf("P"), context.entries.map { it.status.id })
            assertEquals(listOf("P"), f.clientFake.sourceRequests)
        }
    }

    @Test
    fun `a negative depth fails fast at construction`() {
        runTest {
            val e = assertFailsWith<IllegalArgumentException> {
                Fixture(this, listOf(original("P")), maxDepth = -1)
            }
            assertTrue(e.message!!.contains("maxDepth"))
        }
    }

    @Test
    fun `chain cap keeps the nearest ancestors in root-to-toot order`() {
        runTest {
            val chain = listOf(original("root"), original("mid", "root"), original("P", "mid"))
            for ((depth, expected) in listOf(1 to listOf("mid", "P"), 3 to listOf("root", "mid", "P"))) {
                val f = Fixture(this, chain, maxDepth = depth)
                f.feed()
                val context = f.context.build(f.clientFake.remote.getValue("P"))
                assertEquals(expected, context.entries.map { it.status.id }, "depth $depth")
                // sources are fetched in the same order, target included
                assertEquals(expected, f.clientFake.sourceRequests, "depth $depth")
            }
        }
    }

    @Test
    fun `a missing ancestor truncates the chain instead of failing`() {
        runTest {
            val f = Fixture(this, listOf(original("P", "ghost")))
            val context = f.context.build(f.clientFake.remote.getValue("P"))
            assertEquals(listOf("P"), context.entries.map { it.status.id })
            assertTrue(f.clientFake.lookups.isNotEmpty(), "the walk must read the pool")
        }
    }

    @Test
    fun `a translated ancestor is joined in footer order with the markers stripped`() {
        runTest {
            val root = original("root")
            val t1 = translation("T1", parent = "root", seq = 1, total = 2, hash = root.crc32())
            val t2 = translation("T2", parent = "T1", seq = 2, total = 2, hash = root.crc32())
            val p = original("P", "root")
            val f = Fixture(this, listOf(root, t1, t2, p))
            f.clientFake.sources["T1"] = StatusSource("T1", "First line\n🌐${root.crc32()}#1#2", "警告翻译")
            f.clientFake.sources["T2"] = StatusSource("T2", "Second line\n🌐${root.crc32()}#2#2", "")
            f.feed()

            val context = f.context.build(f.clientFake.remote.getValue("P"))
            val rootEntry = context.entries.first { it.status.id == "root" }
            assertEquals("First line\nSecond line", rootEntry.translation)
            assertEquals("警告翻译", rootEntry.translationSpoilerText)

            val target = context.target
            assertEquals("P", target.status.id)
            assertNull(target.translation, "the target never resolves a translation")
        }
    }

    @Test
    fun `the content source fallback keeps generated footers strippable`() {
        runTest {
            // no source override: the fake derives the source from the
            // segment's content, which must keep the footer on its own
            // line for the strip step
            val root = original("root")
            val t1 = translation("T1", parent = "root", hash = root.crc32())
            val p = original("P", "root")
            val f = Fixture(this, listOf(root, t1, p))
            f.feed()

            val context = f.context.build(f.clientFake.remote.getValue("P"))
            val rootEntry = context.entries.first { it.status.id == "root" }
            assertEquals("Translated", rootEntry.translation)
            assertNull(rootEntry.translationSpoilerText)
        }
    }

    @Test
    fun `an ancestor without a complete chain contributes no translation`() {
        runTest {
            val f = Fixture(this, listOf(original("root"), original("P", "root")))
            f.feed()
            val context = f.context.build(f.clientFake.remote.getValue("P"))
            val rootEntry = context.entries.first { it.status.id == "root" }
            assertNull(rootEntry.translation)
            assertNull(rootEntry.translationSpoilerText)
        }
    }

    @Test
    fun `images belong to the target only and respect the cap`() {
        runTest {
            val root = original("root").copy(mediaAttachments = listOf(image("root-media")))
            val p = original("P", "root").copy(
                mediaAttachments = (1..5).map { image("m$it") } + MediaAttachment("v1", "video")
            )
            val f = Fixture(this, listOf(root, p))
            f.feed()

            val context = f.context.build(p)
            assertEquals(MediaLoader.MAX_IMAGES_PER_REQUEST, context.images.size)
            assertEquals((1..4).map { "https://media.example/m$it.png" }, f.media.loaded)
        }
    }

    @Test
    fun `a failing media download is skipped without failing the context`() {
        runTest {
            val p = original().copy(mediaAttachments = listOf(image("bad"), image("good")))
            val f = Fixture(this, listOf(p))
            f.media.handler = { url ->
                if (url.contains("bad")) throw java.io.IOException("broken")
                LoadedMedia(byteArrayOf(9), "image/png")
            }

            val context = f.context.build(p)
            assertEquals(1, context.images.size)
            assertEquals(listOf("https://media.example/bad.png", "https://media.example/good.png"), f.media.loaded)
        }
    }

    @Test
    fun `non-image attachments are never downloaded`() {
        runTest {
            val p = original().copy(
                mediaAttachments = listOf(
                    MediaAttachment("v", "video", url = "https://media.example/v.mp4"),
                    MediaAttachment("a", "audio", url = "https://media.example/a.mp3"),
                    MediaAttachment("g", "gifv", url = "https://media.example/g.mp4"),
                    MediaAttachment("u", "unknown", url = "https://media.example/u"),
                )
            )
            val f = Fixture(this, listOf(p))
            val context = f.context.build(p)
            assertTrue(context.images.isEmpty())
            assertTrue(f.media.loaded.isEmpty())
        }
    }

    @Test
    fun `an accepted quote contributes the quoted toot's raw html and warning`() {
        runTest {
            val quoted = original("Q").copy(
                content = "<p>quoted &amp; <b>bold</b><br>second line</p>",
                spoilerText = "quoted CW",
            )
            val root = original("root")
            val p = original("P", "root").copy(quote = quote(quoted))
            // Q is fetchable, so any lookup for it would show in lookups
            val f = Fixture(this, listOf(root, p, quoted))
            f.feed()

            val context = f.context.build(p)
            assertEquals(
                "<p>quoted &amp; <b>bold</b><br>second line</p>",
                context.target.quotedToot?.content,
            )
            assertEquals("quoted CW", context.target.quotedToot?.spoilerText)
            assertNull(
                context.entries.first { it.status.id == "root" }.quotedToot,
                "the quote context belongs to the target only",
            )
            assertTrue(
                f.clientFake.lookups.none { "Q" in it },
                "the embedded quote costs no lookup, even when Q is fetchable",
            )
        }
    }

    @Test
    fun `a quoted toot already in the history adds no quote context`() {
        runTest {
            val root = original("root")
            val p = original("P", "root").copy(quote = quote(root))
            val f = Fixture(this, listOf(root, p))
            f.feed()

            assertNull(f.context.build(p).target.quotedToot)
        }
    }

    @Test
    fun `an accepted quote without an embedded toot adds no quote context`() {
        runTest {
            val p = original("P").copy(quote = Quote(state = "accepted"))
            val f = Fixture(this, listOf(p))
            f.feed()

            assertNull(f.context.build(p).target.quotedToot)
            assertTrue(f.clientFake.lookups.isEmpty(), "an absent embedded toot costs no lookup")
        }
    }

    @Test
    fun `only an accepted quote contributes context`() {
        runTest {
            // blocked_account and friends embed the quoted toot on the
            // wire, so the state gate alone keeps them out
            for (state in listOf(
                "pending", "rejected", "revoked", "deleted", "unauthorized",
                "blocked_account", "blocked_domain", "muted_account",
            )) {
                val p = original("P").copy(quote = quote(original("Q"), state))
                val f = Fixture(this, listOf(p))
                f.feed()

                assertNull(f.context.build(p).target.quotedToot, state)
                assertTrue(f.clientFake.lookups.isEmpty(), "state $state must contribute nothing")
            }
        }
    }

    @Test
    fun `a quoted toot with neither content nor warning adds no quote context`() {
        runTest {
            val p = original("P").copy(quote = quote(original("Q").copy(content = "")))
            val f = Fixture(this, listOf(p))
            f.feed()

            assertNull(f.context.build(p).target.quotedToot)
        }
    }

    @Test
    fun `a warning-only quoted toot contributes a null content and its warning`() {
        runTest {
            val p = original("P").copy(
                quote = quote(original("Q").copy(content = "", spoilerText = "quoted CW"))
            )
            val f = Fixture(this, listOf(p))
            f.feed()

            val quotedToot = f.context.build(p).target.quotedToot
            assertNull(quotedToot?.content)
            assertEquals("quoted CW", quotedToot?.spoilerText)
        }
    }

    @Test
    fun `an ancestor's quote is never resolved`() {
        runTest {
            val root = original("root").copy(quote = quote(original("Q")))
            val p = original("P", "root")
            val f = Fixture(this, listOf(root, p))
            f.feed()

            val context = f.context.build(p)
            assertNull(context.entries.first { it.status.id == "root" }.quotedToot)
        }
    }

    @Test
    fun `only the target's direct quote is resolved, not the quote chain`() {
        runTest {
            val quoted = original("Q").copy(quote = quote(original("R")))
            val p = original("P").copy(quote = quote(quoted))
            val f = Fixture(this, listOf(p))
            f.feed()

            val context = f.context.build(p)
            assertEquals("<p>中文 body Q</p>", context.target.quotedToot?.content)
        }
    }
}
