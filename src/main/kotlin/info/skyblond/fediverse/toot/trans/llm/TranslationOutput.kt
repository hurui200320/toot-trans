package info.skyblond.fediverse.toot.trans.llm

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The parsed answer of one translation completion: the toot body and
 * the translated content warning (null when the original had none).
 * Produced by [parseTranslationOutput] and adjusted by `Translator`'s
 * answer policy, which mirrors a body-less source to a blank
 * [translation] (see that class).
 * */
data class TranslatedToot(
    val translation: String,
    val spoilerText: String?,
)

/**
 * A completion answer that cannot serve as a translation: not a JSON
 * object, or missing a non-blank `translation` field. The processor
 * treats it as a permanent failure of that toot, see
 * `TranslationProcessor`.
 * */
class TranslationOutputException(message: String) : RuntimeException(message)

private val json = Json { ignoreUnknownKeys = true }

/**
 * Parse the answer contract of [TranslationRequestFactory]'s system
 * prompt: exactly one JSON object
 * `{"translation": "...", "spoiler_text": "..." | null}`.
 *
 * Tolerant where providers commonly stray (a markdown code fence
 * wrapper, extra keys, blank strings for absent values) and strict
 * where it matters: any other shape, or a blank `translation`, fails
 * fast with [TranslationOutputException] and a truncated raw snippet.
 *
 * The CW fallback policy (e.g. reusing the original content warning
 * when the answer omitted it) lives in `Translator`, which knows the
 * source; this function only parses.
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

    // JsonNull is a JsonPrimitive too, so filter with isString:
    // a literal null counts as "no value", not as the text "null"
    val translation = (obj["translation"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
    if (translation.isNullOrBlank()) {
        throw TranslationOutputException(
            "Translation answer has no non-blank \"translation\" field: ${stripped.take(SNIPPET_LENGTH)}"
        )
    }
    val spoilerText = (obj["spoiler_text"] as? JsonPrimitive)
        ?.takeIf { it.isString }
        ?.content
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return TranslatedToot(translation, spoilerText)
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
private const val SNIPPET_LENGTH = 200
