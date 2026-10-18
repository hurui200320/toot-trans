package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromText
import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.removeFooterFromText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FooterTest {

    // --- construction: the fail-fast contract ---

    @Test
    fun `accepts 8 uppercase hex chars with boundary digits`() {
        val zeros = Footer("01234567", 1, 1)
        val effs = Footer("FFFFFFFF", 12, 345)

        assertEquals("01234567", zeros.hash)
        assertEquals(1, zeros.seq)
        assertEquals(1, zeros.total)
        assertEquals("FFFFFFFF", effs.hash)
        assertEquals(12, effs.seq)
        assertEquals(345, effs.total)
    }

    @Test
    fun `rejects a hash that is not 8 uppercase hex chars`() {
        // too short, too long, lowercase, non-hex letter
        for (badHash in listOf("A1B2C3D", "A1B2C3D4E", "a1b2c3d4", "A1B2C3DG")) {
            val e = assertFailsWith<IllegalArgumentException> { Footer(badHash, 1, 1) }
            assertTrue(e.message!!.contains("hash"))
            assertTrue(e.message!!.contains(badHash))
        }
    }

    @Test
    fun `rejects seq outside 1 to total`() {
        // below 1 and above total
        for ((seq, total) in listOf(0 to 1, 3 to 2)) {
            val e = assertFailsWith<IllegalArgumentException> { Footer("A1B2C3D4", seq, total) }
            assertTrue(e.message!!.contains("seq"))
            assertTrue(e.message!!.contains("got $seq"))
        }
    }

    @Test
    fun `rejects a total below 1`() {
        val e = assertFailsWith<IllegalArgumentException> { Footer("A1B2C3D4", 1, 0) }

        assertTrue(e.message!!.contains("total"))
        assertTrue(e.message!!.contains("got 0"))
    }

    @Test
    fun `renders the canonical marker line`() {
        assertEquals("🌐DEADBEEF#2#3", Footer("DEADBEEF", 2, 3).toString())
    }

    // --- matchFooterFromText: happy paths ---

    @Test
    fun `matches a standalone footer`() {
        assertEquals(Footer("A1B2C3D4", 1, 1), "🌐A1B2C3D4#1#1".matchFooterFromText())
    }

    @Test
    fun `matches the footer on the last line of a translation`() {
        assertEquals(
            Footer("A1B2C3D4", 2, 3),
            "Hello world!\nSecond line\n🌐A1B2C3D4#2#3".matchFooterFromText(),
        )
    }

    @Test
    fun `matches across crlf line endings`() {
        assertEquals(Footer("A1B2C3D4", 1, 1), "Hello world!\r\n🌐A1B2C3D4#1#1".matchFooterFromText())
    }

    @Test
    fun `matches a footer preceded by blank paragraph lines`() {
        // an HTML-to-text cleaning may keep paragraph gaps as blank
        // lines; the footer is still the last line
        assertEquals(
            Footer("A1B2C3D4", 1, 2),
            "Paragraph one\n\nParagraph two\n\n🌐A1B2C3D4#1#2".matchFooterFromText(),
        )
    }

    @Test
    fun `tolerates trailing whitespace on the footer line`() {
        assertEquals(Footer("A1B2C3D4", 1, 1), "text\n🌐A1B2C3D4#1#1 \t".matchFooterFromText())
    }

    @Test
    fun `matches a footer followed by trailing blank lines`() {
        // Kotlin's line splitting appends an extra empty line for a
        // trailing terminator, and the caller's cleaning may leave
        // trailing whitespace-only lines; the footer is still the
        // last non-blank line
        assertEquals(Footer("A1B2C3D4", 1, 1), "text\n🌐A1B2C3D4#1#1\n".matchFooterFromText())
        assertEquals(Footer("A1B2C3D4", 1, 1), "text\n🌐A1B2C3D4#1#1\n\n".matchFooterFromText())
        assertEquals(Footer("A1B2C3D4", 1, 1), "text\n🌐A1B2C3D4#1#1\n \t".matchFooterFromText())
    }

    @Test
    fun `matches multi-digit seq and total`() {
        assertEquals(Footer("FFFFFFFF", 12, 345), "🌐FFFFFFFF#12#345".matchFooterFromText())
    }

    @Test
    fun `only the last line counts when several footer lines exist`() {
        assertEquals(Footer("A1B2C3D4", 2, 2), "🌐A1B2C3D4#1#2\n🌐A1B2C3D4#2#2".matchFooterFromText())
    }

    @Test
    fun `round-trips toString through matchFooterFromText`() {
        val footer = Footer("0F0F0F0F", 3, 7)

        assertEquals(footer, footer.toString().matchFooterFromText())
    }

    // --- matchFooterFromText: no footer ---

    @Test
    fun `returns null for text without a footer`() {
        assertNull("Just a normal toot".matchFooterFromText())
        assertNull("A1B2C3D4#1#1 without the globe".matchFooterFromText())
    }

    @Test
    fun `returns null for empty or blank text`() {
        assertNull("".matchFooterFromText())
        assertNull("   ".matchFooterFromText())
        assertNull("\n".matchFooterFromText())
    }

    @Test
    fun `returns null when the footer is not the last line`() {
        assertNull("🌐A1B2C3D4#1#1\nP.S. added later".matchFooterFromText())
    }

    @Test
    fun `returns null when the last line carries text around the marker`() {
        // text glued before the marker: the footer does not own the line
        assertNull("prefix 🌐A1B2C3D4#1#1".matchFooterFromText())
        // indented marker: the regex anchors the marker to line start
        assertNull("text\n  🌐A1B2C3D4#1#1".matchFooterFromText())
        // text after the marker: the line is not just the footer
        assertNull("🌐A1B2C3D4#1#1 trailing".matchFooterFromText())
    }

    // --- matchFooterFromText: footer-shaped but invalid ---

    @Test
    fun `returns null for wrong hash shapes`() {
        // 7 hex chars, 9 hex chars, lowercase, missing entirely
        assertNull("🌐A1B2C3#1#1".matchFooterFromText())
        assertNull("🌐A1B2C3D4E#1#1".matchFooterFromText())
        assertNull("🌐a1b2c3d4#1#1".matchFooterFromText())
        assertNull("🌐#1#1".matchFooterFromText())
    }

    @Test
    fun `returns null for missing or extra footer parts`() {
        assertNull("🌐A1B2C3D4#1".matchFooterFromText())
        assertNull("🌐A1B2C3D4".matchFooterFromText())
        assertNull("🌐A1B2C3D4#1#1#1".matchFooterFromText())
    }

    @Test
    fun `returns null for regex-valid but semantically invalid numbers`() {
        // seq below 1, total below 1, seq above total
        assertNull("🌐A1B2C3D4#0#1".matchFooterFromText())
        assertNull("🌐A1B2C3D4#1#0".matchFooterFromText())
        assertNull("🌐A1B2C3D4#3#2".matchFooterFromText())
    }

    @Test
    fun `returns null for numbers beyond int range`() {
        assertNull("🌐A1B2C3D4#99999999999#99999999999".matchFooterFromText())
        assertNull("🌐A1B2C3D4#1#2147483648".matchFooterFromText())
    }

    // --- removeFooterFromText: the removing twin ---

    @Test
    fun `removes the footer line and keeps the body`() {
        assertEquals("Hello world!", "Hello world!\n🌐A1B2C3D4#1#1".removeFooterFromText())
        assertEquals("line one\nline two", "line one\nline two\n🌐A1B2C3D4#1#2".removeFooterFromText())
    }

    @Test
    fun `keeps the blank lines around the removed footer`() {
        assertEquals("body\n", "body\n🌐A1B2C3D4#1#1\n".removeFooterFromText())
        assertEquals("body\n\n", "body\n🌐A1B2C3D4#1#1\n\n".removeFooterFromText())
        assertEquals("body", "body\n🌐A1B2C3D4#1#1".removeFooterFromText())
    }

    @Test
    fun `normalizes crlf line endings while removing`() {
        assertEquals("Hello\n", "Hello\r\n🌐A1B2C3D4#1#1\r\n".removeFooterFromText())
    }

    @Test
    fun `leaves text without a valid footer unchanged`() {
        assertEquals("Just a normal toot", "Just a normal toot".removeFooterFromText())
        assertEquals("🌐A1B2C3D4#1#1\nmore", "🌐A1B2C3D4#1#1\nmore".removeFooterFromText())
        assertEquals("prefix 🌐A1B2C3D4#1#1", "prefix 🌐A1B2C3D4#1#1".removeFooterFromText())
        assertNull("body\n🌐aaaaaaaa#1#1".matchFooterFromText())
        assertEquals("body\n🌐aaaaaaaa#1#1", "body\n🌐aaaaaaaa#1#1".removeFooterFromText())
    }

    @Test
    fun `strips only the last footer line when several exist`() {
        assertEquals("🌐A1B2C3D4#1#2", "🌐A1B2C3D4#1#2\n🌐A1B2C3D4#2#2".removeFooterFromText())
    }
}
