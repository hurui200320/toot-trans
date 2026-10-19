package info.skyblond.fediverse.toot.trans.llm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parsed answer of one translation completion: the translated toot
 * body and the translated content warning. Produced by
 * [parseTranslationOutput], where a null field means the answer left
 * that part absent or blank, and then shaped against the source by
 * `Translator`'s answer policy, where a null field means the toot does
 * not have that part (see that class).
 * */
data class TranslatedToot(
    val content: String?,
    val spoilerText: String?,
)

/**
 * A completion answer that cannot serve as a translation: not a JSON
 * object, or an answer field that is neither a string nor null. The
 * processor treats it as a permanent failure of that toot (after
 * `Translator`'s answer retries and variant downgrades), see
 * `TranslationProcessor`. `Translator` raises the same exception for
 * an answer that drops a field the source toot has.
 * */
class TranslationOutputException(message: String) : RuntimeException(message)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parse the answer contract of [TranslationRequestFactory]'s system
 * prompt: one JSON object
 * `{"content": "..." | null, "spoiler_text": "..." | null}`.
 *
 * Tolerant where providers commonly stray (a markdown code fence
 * wrapper, extra keys, missing or blank fields for absent values) and
 * strict where it matters: any other shape, or a field of any type but
 * string and null, fails fast with [TranslationOutputException] and a
 * truncated raw snippet. An all-null answer parses as the contract's
 * "nothing to translate" answer; whether it matches the toot under
 * translation is the caller's call.
 *
 * The source-shape policy (a field the source toot does not have is
 * nulled out, and an answer that drops a field the source has is
 * rejected) lives in `Translator`, which knows the source; this
 * function only parses.
 * */
fun parseTranslationOutput(text: String): TranslatedToot {
    val stripped = stripCodeFence(text)
    val element = try {
        json.parseToJsonElement(stripped)
    } catch (_: SerializationException) {
        throw TranslationOutputException(
            "Translation answer is not valid JSON: ${stripped.take(SNIPPET_LENGTH)}"
        )
    }
    val obj = element as? JsonObject
        ?: throw TranslationOutputException(
            "Translation answer is not a JSON object: ${stripped.take(SNIPPET_LENGTH)}"
        )

    val content = obj.optionalString("content", stripped)
    val spoilerText = obj.optionalString("spoiler_text", stripped)
    return TranslatedToot(content, spoilerText)
}

/**
 * One contract field of an answer object: a string (trimmed, blank
 * counts as absent), a literal null, or a missing key; anything else
 * fails fast.
 * */
private fun JsonObject.optionalString(key: String, raw: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    // after the null check above, the only primitives left are
    // literals: isString rejects the non-string ones (numbers,
    // booleans) so they fail fast below
    val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.trim()
        ?: throw TranslationOutputException(
            "Translation answer field \"$key\" is not a string or null: ${raw.take(SNIPPET_LENGTH)}"
        )
    return text.takeIf { it.isNotBlank() }
}

/**
 * Drop a wrapping markdown code fence, the way weaker models often
 * package JSON answers despite the prompt. Only a fence around the
 * whole answer is removed; anything else is left to the parser and
 * its error reporting.
 * */
private fun stripCodeFence(raw: String): String {
    val text = raw.trim()
    if (!text.startsWith("```") || !text.endsWith("```")) return text
    val firstNewline = text.indexOf('\n')
    if (firstNewline < 0) return text
    return text.substring(firstNewline + 1, text.length - 3).trim()
}

/**
 * Upper bound of the raw answer snippet in [TranslationOutputException]
 * messages, so a pathological answer cannot bloat the log line.
 * */
internal const val SNIPPET_LENGTH = 200
