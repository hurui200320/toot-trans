package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.removeFooterFromText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The greedy segmentation of one translated body into footer-carrying
 * segments. The footer contract itself is covered by [FooterTest]; here
 * the packing, the reserved budget and the boundaries are pinned.
 * */
class TranslationChunksTest {
    private val hash = "A1B2C3D4"

    /** No CW: 500 - 40 reserve. */
    private val limit = MAX_TOOT_CHARS - FOOTER_CHAR_RESERVE

    /**
     * The body of a rendered segment: `removeFooterFromText` keeps the
     * blank separator line before the footer (that is its contract),
     * so strip that too to recover the exact packed body.
     * */
    private fun bodyOf(segment: String): String =
        segment.removeFooterFromText().removeSuffix("\n")

    @Test
    fun `short translation becomes a single footer segment`() {
        assertEquals(
            listOf("Hello world\n\n🌐$hash#1#1"),
            chunkTranslation("Hello world", null, hash),
        )
    }

    @Test
    fun `body is trimmed and blank bodies fail fast`() {
        assertEquals(
            listOf("hello\n\n🌐$hash#1#1"),
            chunkTranslation("  hello \n\n", null, hash),
        )
        for (blank in listOf("", " ", "\n\t")) {
            assertFailsWith<IllegalArgumentException> { chunkTranslation(blank, null, hash) }
        }
    }

    @Test
    fun `exact limit fits one segment and one more char splits`() {
        val single = chunkTranslation("a".repeat(limit), null, hash)
        assertEquals(1, single.size)
        assertEquals("a".repeat(limit) + "\n\n🌐$hash#1#1", single.single())

        val split = chunkTranslation("a".repeat(limit + 1), null, hash)
        assertEquals(2, split.size)
        assertEquals("a".repeat(limit) + "\n\n🌐$hash#1#2", split[0])
        assertEquals("a\n\n🌐$hash#2#2", split[1])
    }

    @Test
    fun `short paragraphs pack with newline separators`() {
        val chunks = chunkTranslation("first\nsecond", null, hash)
        assertEquals(listOf("first\nsecond\n\n🌐$hash#1#1"), chunks)
    }

    @Test
    fun `over-limit paragraphs split and pack greedily`() {
        val paragraph = "x".repeat(300)
        val chunks = chunkTranslation("$paragraph\n$paragraph\n$paragraph", null, hash)
        // 300 + 1 + 300 exceeds the 460 budget, so each paragraph gets its own segment
        assertEquals(3, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            assertEquals("$paragraph\n\n🌐$hash#${index + 1}#3", chunk)
        }
    }

    @Test
    fun `over-limit paragraph splits at sentence boundaries`() {
        val first = "A".repeat(300) + "."
        val second = "B".repeat(300) + "."
        val chunks = chunkTranslation("$first $second", null, hash)
        assertEquals(2, chunks.size)
        // the space consumed by the split is not re-added: it only
        // separated the two sentences, which now live in two segments
        assertEquals("$first\n\n🌐$hash#1#2", chunks[0])
        assertEquals("$second\n\n🌐$hash#2#2", chunks[1])
    }

    @Test
    fun `sentences pack into one segment while they fit`() {
        val first = "A".repeat(100) + "."
        val second = "B".repeat(100) + "."
        val third = "C".repeat(100) + "."
        val chunks = chunkTranslation("$first $second $third", null, hash)
        assertEquals(1, chunks.size)
        assertEquals("$first $second $third\n\n🌐$hash#1#1", chunks.single())
    }

    @Test
    fun `unbreakable runs hard-split at the budget and lose nothing`() {
        val text = "x".repeat(1000)
        val chunks = chunkTranslation(text, null, hash)
        assertEquals(3, chunks.size)
        val bodies = chunks.map(::bodyOf)
        assertEquals(
            listOf("x".repeat(limit), "x".repeat(limit), "x".repeat(1000 - 2 * limit)),
            bodies,
        )
        assertEquals(text, bodies.joinToString(""))
    }

    @Test
    fun `hard splits never cut a surrogate pair`() {
        // 1 + 300 surrogate pairs: the 460-char cut would land on the
        // low half, so the split snaps one unit back
        val text = "x" + "🌍".repeat(300)
        val chunks = chunkTranslation(text, null, hash)
        for (chunk in chunks) {
            val body = bodyOf(chunk)
            assertTrue(body.isNotEmpty())
            assertFalse(Character.isHighSurrogate(body.last()), "cut inside a surrogate pair: ${body.takeLast(3)}")
        }
        assertEquals(text, chunks.joinToString("") { bodyOf(it) })
    }

    @Test
    fun `the content warning shrinks the budget on every segment`() {
        val cw = "W".repeat(100)
        val text = "a".repeat(limit - FOOTER_CHAR_RESERVE + 1)
        // fits without the CW, splits with it
        assertEquals(1, chunkTranslation(text, null, hash).size)
        val chunks = chunkTranslation(text, cw, hash)
        assertEquals(2, chunks.size)
        // the CW is not part of the body (the poster attaches it), and
        // the budget shrinks by exactly its length
        val budget = limit - cw.length
        assertEquals("a".repeat(budget) + "\n\n🌐$hash#1#2", chunks[0])
        assertEquals("a".repeat(text.length - budget) + "\n\n🌐$hash#2#2", chunks[1])
    }

    @Test
    fun `a blank content warning counts as none`() {
        assertEquals(
            listOf("Hello world\n\n🌐$hash#1#1"),
            chunkTranslation("Hello world", "  \n ", hash),
        )
    }

    @Test
    fun `a content warning that leaves under two chars of budget fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            chunkTranslation("hello", "W".repeat(MAX_TOOT_CHARS), hash)
        }
        // one char of budget cannot honor the code-point boundary rule,
        // so the boundary is two, not one
        val leavingOne = "W".repeat(MAX_TOOT_CHARS - FOOTER_CHAR_RESERVE - 1)
        assertFailsWith<IllegalArgumentException> { chunkTranslation("hello", leavingOne, hash) }
        // two chars of budget are accepted: "he" / "ll" / "o"
        val leavingTwo = "W".repeat(MAX_TOOT_CHARS - FOOTER_CHAR_RESERVE - 2)
        assertEquals(3, chunkTranslation("hello", leavingTwo, hash).size)
    }

    @Test
    fun `many segments carry growing sequence and total markers`() {
        val paragraph = "x".repeat(limit)
        val text = (1..12).joinToString("\n") { paragraph }
        val chunks = chunkTranslation(text, null, hash)
        assertEquals(12, chunks.size)
        assertTrue(chunks.first().endsWith("\n\n🌐$hash#1#12"))
        assertTrue(chunks.last().endsWith("\n\n🌐$hash#12#12"))
    }

    @Test
    fun `every rendered segment stays within the instance limit`() {
        val cw = "W".repeat(50)
        val text = ("word ".repeat(400) + "🌍".repeat(40))
        val chunks = chunkTranslation(text, cw, hash)
        assertTrue(chunks.size > 1)
        chunks.forEachIndexed { index, chunk ->
            val spoilerLength = if (index == 0) cw.length else 0
            assertTrue(
                spoilerLength + chunk.length <= MAX_TOOT_CHARS,
                "segment ${index + 1} exceeds $MAX_TOOT_CHARS chars",
            )
        }
    }

    @Test
    fun `a malformed hash fails fast through the footer validation`() {
        assertFailsWith<IllegalArgumentException> { chunkTranslation("hello", null, "lowercase") }
    }
}
