package info.skyblond.fediverse.toot.trans.masto.client.statuses

import info.skyblond.fediverse.toot.trans.llm.Footer
import info.skyblond.fediverse.toot.trans.masto.statuses.now
import info.skyblond.fediverse.toot.trans.masto.statuses.original
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class StatusFingerprintTest {
    @Test
    fun `checksum has fixed UTF8 field order and uppercase golden values`() {
        assertEquals("8BB1D29A", original().copy(content = null, spoilerText = null).crc32())
        assertEquals("8641BED6", original().copy(content = "<p>hello</p>", spoilerText = "").crc32())
        val unicode = original().copy(content = "<p>你好 🌍</p>", spoilerText = "警告")
        assertEquals("D2C856F0", unicode.crc32())
        assertEquals("🌐D2C856F0#1#1", Footer(unicode.crc32(), 1, 1).toString())
    }

    @Test
    fun `null and empty fields match and body or CW edits change checksum`() {
        assertEquals(original().copy(content = null).crc32(), original().copy(content = "").crc32())
        assertEquals(original().copy(spoilerText = null).crc32(), original().copy(spoilerText = "").crc32())
        assertNotEquals(original().crc32(), original().copy(content = "<p>edited</p>").crc32())
        assertNotEquals(original().crc32(), original().copy(spoilerText = "new CW").crc32())
        assertEquals(original().crc32(), original().copy(editedAt = now, language = "fr").crc32())
    }
}
