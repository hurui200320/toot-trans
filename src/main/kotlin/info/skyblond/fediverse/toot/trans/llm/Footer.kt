package info.skyblond.fediverse.toot.trans.llm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * The marker line this bot appends as the last line of every
 * English translation reply: `🌐{hash}#{seq}#{total}`.
 *
 * The marker makes the bot's own replies self-describing, so the
 * poller can recognize them and rebuild multi-reply chains with no
 * local state:
 * - [hash]: 8 uppercase hex chars (0-9A-F) derived from the source
 *   toot's text (content and spoiler text), identifying *which
 *   version* of the original was translated — an edited original
 *   no longer matches the hash of its existing translation.
 * - [seq] and [total]: the 1-based index and the count of the
 *   segments the full translation was split into. A chain whose
 *   tail marker has `seq == total` is complete; a shorter one is
 *   half-posted and must be cleaned up before retranslating.
 *
 * Examples: a reply that fit in one post ends with `🌐A1B2C3D4#1#1`;
 * a translation split into 3 posts ends its segments with `#1#3`,
 * `#2#3`, `#3#3` respectively.
 *
 * The `#` characters never parse as hashtags on Mastodon: a
 * hashtag's `#` must start the text or follow a non-word
 * character (whitespace, punctuation) and its name must not be
 * all digits, while each `#` here is glued to a preceding hash
 * char or digit and followed by digits only — the marker travels
 * as plain text.
 *
 * [toString] renders the marker and [String.matchFooterFromText] parses it
 * back, so markers round-trip losslessly. Construction validates
 * fail-fast: [hash] must be exactly 8 chars of 0-9A-F, [total]
 * must be at least 1, and [seq] must be in 1..[total].
 * */
data class Footer(
    val hash: String,
    val seq: Int,
    val total: Int
) {
    init {
        require(hash.length == 8 && hash.all { it in '0'..'9' || it in 'A'..'F' }) {
            "Footer hash must be 8 chars long and all chars must be 0-9A-F, got \"$hash\""
        }
        // total is checked before seq so a bad total is not
        // misreported as a seq problem: 1..total is empty then
        require(total >= 1) {
            "Footer total must be greater than or equal to 1, got $total"
        }
        require(seq in 1..total) {
            "Footer seq must be in range [1, $total], got $seq"
        }
    }

    override fun toString(): String = """🌐$hash#$seq#$total"""

    companion object {
        private val logger = KotlinLogging.logger { }

        private val footerRegex = Regex("""🌐([0-9A-F]{8})#(\d+)#(\d+)\s*""")

        /**
         * Match the footer on the last non-blank line of [this]
         * plain text, or null when there is no valid footer.
         *
         * Pass composing text directly; for a status's HTML content,
         * use [matchFooterFromContent] instead. Trailing blank lines
         * are tolerated — including the extra empty line Kotlin's line
         * splitting appends for a trailing terminator — but any
         * non-blank line after the marker disqualifies the marker.
         *
         * A last line that carries text before or after the
         * marker, or whose marker merely looks regex-shaped but
         * fails [Footer]'s validation (e.g. `#0#1`, `#3#2`, or
         * numbers beyond Int range) yields null: callers see "no
         * footer", never an exception.
         * */
        fun String.matchFooterFromText(): Footer? {
            // the footer, if any, owns the last non-blank line per
            // the contract above; skipping blanks also absorbs the
            // extra line a trailing terminator produces
            val lastLine = lineSequence().lastOrNull { it.isNotBlank() } ?: return null
            val match = footerRegex.matchEntire(lastLine) ?: return null
            // \d+ happily matches numbers too large for Int
            val seq = match.groupValues[2].toIntOrNull() ?: return null
            val total = match.groupValues[3].toIntOrNull() ?: return null
            // the regex is coarser than the constructor: shapes like
            // #0#1 or #3#2 pass the regex but fail validation
            return try {
                Footer(match.groupValues[1], seq, total)
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        /**
         * Match a footer in the HTML `content` of a locally authored
         * post on vanilla Mastodon, without any status-source API call.
         * Arbitrary federated rich text and customized forks are outside
         * this adapter's compatibility contract.
         *
         * Vanilla's TextFormatter uses `<p>` for paragraphs and `<br>`
         * for single line breaks. Add paragraph boundaries as text nodes;
         * Jsoup's `wholeText()` supplies the `<br>` newlines, decodes
         * entities, and joins inline elements without extra separators.
         * This preserves logical lines, not exact composing text or
         * browser layout. See Mastodon's `app/lib/text_formatter.rb`.
         *
         * Only pass `content`, not preview cards or other status fields.
         * The result is passed untrimmed to [matchFooterFromText], which
         * owns the matching and whitespace rules.
         * */
        fun String.matchFooterFromContent(): Footer? {
            val body = Jsoup.parseBodyFragment(this).body()
            body.select("p").forEach { paragraph ->
                paragraph.before(TextNode("\n"))
                paragraph.after(TextNode("\n"))
            }
            // Note we must NOT trim here. As the content might be
            // `  <valid>`, note the leading space.
            // This should not be considered as valid footer.
            // And trim will make regex accept this
            val text = body.wholeText()
            logger.debug { "Cleaning HTML content field:\nContent:\n$this\nText:\n$text" }
            return text.matchFooterFromText()
        }
    }
}
