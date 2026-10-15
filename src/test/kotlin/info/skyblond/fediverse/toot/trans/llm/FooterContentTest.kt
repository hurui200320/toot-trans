package info.skyblond.fediverse.toot.trans.llm

import info.skyblond.fediverse.toot.trans.llm.Footer.Companion.matchFooterFromContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FooterContentTest {

    @Test
    fun `matches a standalone footer paragraph`() {
        assertEquals(Footer("A1B2C3D4", 1, 1), "<p>🌐A1B2C3D4#1#1</p>".matchFooterFromContent())
    }

    @Test
    fun `matches after different br spellings within a paragraph`() {
        for (br in listOf("<br>", "<br/>", "<br />", "<BR>")) {
            val content = "<p>Hello world!${br}🌐A1B2C3D4#2#3</p>"
            assertEquals(Footer("A1B2C3D4", 2, 3), content.matchFooterFromContent(), content)
        }
    }

    @Test
    fun `matches a separate final paragraph without source newlines`() {
        assertEquals(
            Footer("A1B2C3D4", 1, 1),
            "<p>First paragraph</p><p>Second paragraph</p><p>🌐A1B2C3D4#1#1</p>".matchFooterFromContent(),
        )
    }

    @Test
    fun `tolerates repeated breaks trailing whitespace and empty paragraphs`() {
        assertEquals(
            Footer("A1B2C3D4", 1, 1),
            "<p>Hello<br><br>🌐A1B2C3D4#1#1 \t<br><br></p><p></p><p> \t</p>\n"
                .matchFooterFromContent(),
        )
    }

    @Test
    fun `joins footer text across inline elements`() {
        assertEquals(
            Footer("A1B2C3D4", 1, 1),
            "<p>Hello<br>🌐<span>A1B2</span><span>C3D4</span><a href=\"https://example.org\">#1</a>#1</p>"
                .matchFooterFromContent(),
        )
    }

    @Test
    fun `decodes entities in footer text`() {
        assertEquals(
            Footer("A1B2C3D4", 1, 1),
            "<p>&#x1F310;A1B2C3D4&#35;1&#35;1</p>".matchFooterFromContent(),
        )
    }

    @Test
    fun `keeps literal newlines in paragraph text`() {
        assertEquals(
            Footer("A1B2C3D4", 1, 1),
            "<p>Hello\r\n🌐A1B2C3D4#1#1</p>".matchFooterFromContent(),
        )
    }

    @Test
    fun `only the last footer line counts`() {
        assertEquals(
            Footer("A1B2C3D4", 2, 2),
            "<p>🌐A1B2C3D4#1#2</p><p>🌐A1B2C3D4#2#2</p>".matchFooterFromContent(),
        )
        assertNull("<p>🌐A1B2C3D4#1#1</p><p>🌐A1B2C3D4#0#1</p>".matchFooterFromContent())
    }

    @Test
    fun `rejects a footer followed by text in any paragraph position`() {
        for (content in listOf(
            "<p>🌐A1B2C3D4#1#1</p><p>More text</p>",
            "<p>🌐A1B2C3D4#1#1<br>More text</p>",
            "<p>🌐A1B2C3D4#1#1</p>More text",
        )) {
            assertNull(content.matchFooterFromContent(), content)
        }
    }

    @Test
    fun `rejects text around the footer even across inline elements`() {
        for (content in listOf(
            "<p>Example: <span>🌐A1B2C3D4#1#1</span></p>",
            "<p>🌐A1B2C3D4#1#1 trailing</p>",
            "<p><span>🌐A1B2C3D4#1#1</span><span> trailing</span></p>",
        )) {
            assertNull(content.matchFooterFromContent(), content)
        }
    }

    @Test
    fun `preserves indentation instead of trimming it away`() {
        assertNull("<p>  🌐A1B2C3D4#1#1</p>".matchFooterFromContent())
        assertNull("<p>Hello<br>\t🌐A1B2C3D4#1#1</p>".matchFooterFromContent())
    }

    @Test
    fun `rejects a link after the footer`() {
        assertNull(
            "<p>🌐A1B2C3D4#1#1</p><p><a href=\"https://example.org\">example.org</a></p>"
                .matchFooterFromContent(),
        )
    }

    @Test
    fun `does not interpret escaped tags as line breaks`() {
        assertNull("<p>Hello&lt;br&gt;🌐A1B2C3D4#1#1</p>".matchFooterFromContent())
    }

    @Test
    fun `does not read markers from attributes or comments`() {
        assertNull("<p title=\"🌐A1B2C3D4#1#1\">Hello</p>".matchFooterFromContent())
        assertNull("<p>Hello</p><!-- 🌐A1B2C3D4#1#1 -->".matchFooterFromContent())
    }

    @Test
    fun `ignores non-text data and trailing comments`() {
        assertEquals(
            Footer("A1B2C3D4", 1, 1),
            "<p>🌐A1B2C3D4#1#1</p><!-- comment --><script>data</script><style>data</style>"
                .matchFooterFromContent(),
        )
    }

    @Test
    fun `returns null for empty blank or ordinary content`() {
        for (content in listOf("", " \n\t", "<p></p>", "<p> \t<br></p>", "<p>Hello world</p>")) {
            assertNull(content.matchFooterFromContent(), content)
        }
    }

    @Test
    fun `returns null for invalid footer shapes and numbers`() {
        for (marker in listOf(
            "🌐A1B2C3#1#1",
            "🌐a1b2c3d4#1#1",
            "🌐A1B2C3D4#1",
            "🌐A1B2C3D4#1#1#1",
            "🌐A1B2C3D4#0#1",
            "🌐A1B2C3D4#1#0",
            "🌐A1B2C3D4#3#2",
            "🌐A1B2C3D4#2147483648#2147483648",
            "🌐A1B2C3D4#1#2147483648",
        )) {
            assertNull("<p>Hello</p><p>$marker</p>".matchFooterFromContent(), marker)
        }
    }
}
