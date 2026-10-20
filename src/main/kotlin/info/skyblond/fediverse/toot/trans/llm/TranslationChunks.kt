package info.skyblond.fediverse.toot.trans.llm

/**
 * Mastodon's default per-toot character limit, `spoiler_text.length +
 * text.length <= [MAX_TOOT_CHARS]`. Instances may customize it (and
 * expose it via `GET /api/v2/instance`); until that is read, the
 * default applies and [FOOTER_CHAR_RESERVE] keeps the segments well
 * below it.
 * */
const val MAX_TOOT_CHARS = 500

/**
 * Characters reserved on every segment for the trailing footer line
 * (`\n` + `🌐{hash}#{seq}#{total}`, 15-19 UTF-16 units depending on
 * the digit counts) plus slack. The slack absorbs the two counting
 * rules our own length cannot see: the instance scores a URL shorter
 * than 23 characters as 23 — one such URL fits the slack, several
 * short URLs in one packed segment can still overflow — and it counts
 * code points where Kotlin counts UTF-16 units, so Kotlin's count is
 * the larger one. Single-sourced here; `chunkTranslation` derives each
 * segment's budget from it.
 * */
const val FOOTER_CHAR_RESERVE = 40

/**
 * Render [translation] as the one or more reply segments carrying
 * [hash], ready to post in order (each a reply to the previous one,
 * see `TranslationPoster`). The footer contract itself lives on
 * [Footer].
 *
 * The body is split greedily, preferring paragraph boundaries (`\n`),
 * then sentence boundaries, then hard cuts at the segment budget; a
 * hard cut is snapped to a code point boundary so a surrogate pair is
 * never halved. The budget is uniform across segments: [spoilerText]
 * is only attached to the first posted segment, but its length is
 * reserved on all of them (the simpler always-safe choice).
 *
 * [translation] must not be blank (the LLM contract guarantees
 * non-blank answers; blank reaching here is a caller bug), and
 * [spoilerText] must leave a budget of at least two characters —
 * one character cannot honor the code-point boundary rule of the
 * hard cut — fail-fast instead of posting a broken segment.
 * */
fun chunkTranslation(
    translation: String,
    spoilerText: String?,
    hash: String,
): List<String> {
    val text = translation.trim()
    require(text.isNotBlank()) { "translation must not be blank" }
    val contentWarning = spoilerText?.takeIf { it.isNotBlank() }
    val limit = MAX_TOOT_CHARS - FOOTER_CHAR_RESERVE - (contentWarning?.length ?: 0)
    require(limit >= 2) {
        "content warning of ${contentWarning?.length} chars leaves no usable room for the " +
                "translation within the $MAX_TOOT_CHARS char limit"
    }

    val chunks = packGreedy(splitIntoPieces(text, limit), limit)
    val total = chunks.size
    // Footer validates the hash; crc32() always produces the expected shape
    return chunks.mapIndexed { index, body -> "$body\n\n${Footer(hash, index + 1, total)}" }
}

/**
 * A chunkable unit of text with the separator that precedes it when it
 * is packed next to the previous piece: `\n` for paragraph starts, a
 * space for sentences inside one paragraph, nothing for the
 * continuation of a hard-split sentence.
 * */
private class Piece(val separator: String, val text: String)

/**
 * Split [text] into pieces that each fit [limit], preserving as much
 * structure as the limit allows: paragraphs, then sentences, then hard
 * cuts.
 * */
private fun splitIntoPieces(text: String, limit: Int): List<Piece> {
    val pieces = mutableListOf<Piece>()
    text.split('\n').forEach { paragraph ->
        if (paragraph.length <= limit) {
            pieces += Piece("\n", paragraph)
        } else {
            addParagraphSentences(paragraph, limit, pieces)
        }
    }
    return pieces
}

/**
 * Add the sentences of one over-long [paragraph]: a sentence starts a
 * new paragraph piece (`\n` separator) only when it is the first one;
 * the following ones sit inside the same paragraph, so they are
 * separated by a space (which the split consumed).
 * */
private fun addParagraphSentences(paragraph: String, limit: Int, pieces: MutableList<Piece>) {
    var separator = "\n"
    SENTENCE_BOUNDARY.split(paragraph).forEach { sentence ->
        if (sentence.length <= limit) {
            pieces += Piece(separator, sentence)
        } else {
            addHardSplits(sentence, limit, separator, pieces)
        }
        separator = " "
    }
}

/**
 * Cut [sentence] into [limit]-sized pieces (it is already known to be
 * longer than [limit]); the first piece keeps [separator], the rest
 * continue directly. A cut that would land between a surrogate pair
 * moves one code unit earlier, so substring never halves a character.
 * */
private fun addHardSplits(
    sentence: String,
    limit: Int,
    separator: String,
    pieces: MutableList<Piece>,
) {
    var start = 0
    var first = true
    while (start < sentence.length) {
        var end = minOf(start + limit, sentence.length)
        if (end < sentence.length && Character.isLowSurrogate(sentence[end])) end--
        // unreachable with the two-char budget guard, but never fail
        // to progress, even on malformed input
        if (end <= start) end = start + 1
        pieces += Piece(if (first) separator else "", sentence.substring(start, end))
        first = false
        start = end
    }
}

/**
 * Greedily pack [pieces] into chunks of at most [limit] characters; a
 * piece only ever starts a chunk, so its separator is dropped exactly
 * when a chunk boundary precedes it. Blank chunks (from blank
 * paragraphs) are trimmed away.
 * */
private fun packGreedy(pieces: List<Piece>, limit: Int): List<String> {
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    pieces.forEach { piece ->
        val separator = if (current.isEmpty()) "" else piece.separator
        if (current.length + separator.length + piece.text.length <= limit) {
            current.append(separator).append(piece.text)
        } else {
            if (current.isNotEmpty()) chunks += current.toString()
            current.setLength(0)
            current.append(piece.text)
        }
    }
    if (current.isNotEmpty()) chunks += current.toString()
    return chunks.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Sentence boundary for the split preference: after `.`, `!` or `?`
 * followed by whitespace. Targets whose language does not use these
 * marks simply fall through to hard cuts, which always fit.
 * */
private val SENTENCE_BOUNDARY = Regex("""(?<=[.!?])\s+""")
