package info.skyblond.fediverse.toot.trans.masto.statuses

import info.skyblond.fediverse.toot.trans.llm.Footer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * The reconstruction is pure, so these tests only use the shared
 * [original]/[translation] builders from `StatusBuilders.kt`.
 * */
class TranslationChainTest {

    private fun assertTranslated(statuses: List<Status>, vararg chainIds: String) {
        val state = findTranslationChainState("P", statuses, "en")
        val translated = assertIs<TranslationChainState.Translated>(state)
        assertEquals(chainIds.toList(), translated.chain.segments.map { it.first.id })
    }

    private fun assertNeedsTranslation(
        statuses: List<Status>,
        reason: TranslationChainState.Reason,
        vararg obsoleteIds: String,
    ) {
        val state = findTranslationChainState("P", statuses, "en")
        val needs = assertIs<TranslationChainState.NeedsTranslation>(state)
        assertEquals(reason, needs.reason)
        assertEquals(obsoleteIds.toList(), needs.obsolete.map { it.id })
    }

    @Test
    fun `without segments the original is absent with nothing to delete`() {
        assertNeedsTranslation(listOf(original()), TranslationChainState.Reason.ABSENT)
    }

    @Test
    fun `a single complete segment is accepted as translated`() {
        assertTranslated(listOf(original(), translation("T")), "T")
    }

    @Test
    fun `a complete multi segment chain is accepted in seq order`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 3),
            translation("T2", parent = "T1", seq = 2, total = 3),
            translation("T3", parent = "T2", seq = 3, total = 3),
        )
        assertTranslated(statuses, "T1", "T2", "T3")
    }

    @Test
    fun `accepted segments carry their parsed footers in seq order`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 2),
            translation("T2", parent = "T1", seq = 2, total = 2),
        )
        val state = findTranslationChainState("P", statuses, "en")
        val translated = assertIs<TranslationChainState.Translated>(state)
        assertEquals(
            listOf(Footer(original().crc32(), 1, 2), Footer(original().crc32(), 2, 2)),
            translated.chain.segments.map { it.second },
        )
    }

    @Test
    fun `an edited original keeps its complete stale chain under the one shot policy`() {
        val posted = translation("T", hash = original().crc32())
        val edited = original().copy(content = "<p>edited after posting</p>")
        assertNotEquals(original().crc32(), edited.crc32())
        assertTranslated(listOf(edited, posted), "T")
    }

    @Test
    fun `a partial chain is invalid and doomed leaf first`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 3),
            translation("T2", parent = "T1", seq = 2, total = 3),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "T1")
    }

    @Test
    fun `a seq gap is invalid`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 3),
            translation("T2", parent = "T1", seq = 3, total = 3),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "T1")
    }

    @Test
    fun `a total mismatch is invalid`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 3),
            translation("T2", parent = "T1", seq = 2, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "T1")
    }

    @Test
    fun `mixed hashes are invalid`() {
        val statuses = listOf(
            original(),
            translation("T1", hash = "AAAAAAAA", seq = 1, total = 2),
            translation("T2", parent = "T1", hash = "BBBBBBBB", seq = 2, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "T1")
    }

    @Test
    fun `a fork is invalid and every branch is doomed`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 2),
            translation("T2a", parent = "T1", seq = 2, total = 2),
            translation("T2b", parent = "T1", seq = 2, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2a", "T2b", "T1")
    }

    @Test
    fun `a valid chain with an extra leftover root is invalid and everything is doomed`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 4),
            translation("T2", parent = "T1", seq = 2, total = 4),
            translation("T3", parent = "T2", seq = 3, total = 4),
            translation("T4", parent = "T3", seq = 4, total = 4),
            translation("X", parent = "P", seq = 5, total = 5),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T4", "T3", "T2", "T1", "X")
    }

    @Test
    fun `a complete chain with a leftover tail segment is invalid and everything is doomed`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 4),
            translation("T2", parent = "T1", seq = 2, total = 4),
            translation("T3", parent = "T2", seq = 3, total = 4),
            translation("T4", parent = "T3", seq = 4, total = 4),
            translation("X", parent = "T4", seq = 5, total = 5),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "X", "T4", "T3", "T2", "T1")
    }

    @Test
    fun `duplicate complete chains are invalid and everything is doomed`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 2),
            translation("U1", seq = 1, total = 2),
            translation("T2", parent = "T1", seq = 2, total = 2),
            translation("U2", parent = "U1", seq = 2, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "U2", "T1", "U1")
    }

    @Test
    fun `a valid chain next to a partial remnant is invalid and everything is doomed`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 1),
            translation("R1", seq = 1, total = 3),
            translation("R2", parent = "R1", seq = 2, total = 3),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "R2", "T1", "R1")
    }

    @Test
    fun `other language chains are ignored and never deleted`() {
        val statuses = listOf(
            original(),
            translation("S1", language = "es", seq = 1, total = 2),
            translation("S2", parent = "S1", language = "es", seq = 2, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.ABSENT)
    }

    @Test
    fun `other language chains do not disturb a valid target language chain`() {
        val statuses = listOf(
            original(),
            translation("T"),
            translation("S", language = "es"),
        )
        assertTranslated(statuses, "T")
    }

    @Test
    fun `a plain target language comment does not disturb a valid chain`() {
        val comment = original("comment", "P").copy(
            language = "en",
            content = "<p>plain en comment</p>",
        )
        val statuses = listOf(original(), translation("T"), comment)
        assertTranslated(statuses, "T")
    }

    @Test
    fun `comments without footers and unrelated segments are untouched`() {
        val statuses = listOf(
            original(),
            original("comment", parent = "P"),
            translation("Q1", parent = "Q"),
            translation("N").copy(inReplyToId = null),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.ABSENT)
    }

    @Test
    fun `a dangling tail is unreachable and stays untouched`() {
        val statuses = listOf(
            original(),
            translation("T2", parent = "T1", seq = 2, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.ABSENT)
    }

    @Test
    fun `a repeated status id is invalid and terminates`() {
        val statuses = listOf(
            original(),
            translation("T1", seq = 1, total = 2),
            translation("T2", parent = "T1", seq = 2, total = 2),
            translation("T1", parent = "T2", seq = 1, total = 2),
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "T1")
    }

    @Test
    fun `duplicate input entries fail closed with each id doomed once`() {
        val t1 = translation("T1", seq = 1, total = 2)
        val statuses = listOf(
            original(),
            t1,
            translation("T2", parent = "T1", seq = 2, total = 2),
            t1,
        )
        assertNeedsTranslation(statuses, TranslationChainState.Reason.INVALID, "T2", "T1")
    }

    @Test
    fun `blank ids fail fast`() {
        assertFailsWith<IllegalArgumentException> {
            findTranslationChainState(" ", emptyList(), "en")
        }
        assertFailsWith<IllegalArgumentException> {
            findTranslationChainState("P", emptyList(), " ")
        }
    }
}
