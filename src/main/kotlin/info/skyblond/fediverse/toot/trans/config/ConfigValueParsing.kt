package info.skyblond.fediverse.toot.trans.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.enums.enumEntries
import kotlin.time.Duration

/**
 * Parse a duration from a raw config value, for example `500ms`, `1s`,
 * `2m`, `24h`, or an ISO-8601 duration like `PT24H`; see
 * [Duration.parseOrNull] for all accepted forms.
 *
 * Blank input means "not configured" and returns null so the [ConfigStore]
 * fallback can continue; any other unparseable value throws
 * [IllegalArgumentException] so a typo fails fast instead of silently
 * falling back to a default. The thrown message echoes the raw value, so
 * this helper must not be used for secret settings.
 * */
fun parseDurationConfig(name: String, raw: String?): Duration? {
    if (raw.isNullOrBlank()) return null
    return Duration.parseOrNull(raw.trim())
        ?: throw IllegalArgumentException("Config `$name` has an invalid duration value: `$raw`")
}

/**
 * Parse an integer from a raw config value, for example `1000`.
 *
 * Blank input, fail-fast on invalid values, and echoing the raw value in
 * the error message all follow [parseDurationConfig].
 * */
fun parseIntConfig(name: String, raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    return raw.trim().toIntOrNull()
        ?: throw IllegalArgumentException("Config `$name` has an invalid integer value: `$raw`")
}

/**
 * Parse a plain string from a raw config value, trimming surrounding
 * whitespace so the value can be used as-is.
 *
 * Blank input means "not configured" and returns null so the [ConfigStore]
 * fallback can continue, same as [parseDurationConfig]; any other value is
 * accepted, so this never throws and needs no config name for an error.
 * */
fun parseStringConfig(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    return raw.trim()
}

/**
 * Parse an enum value from a raw config value, matched against the
 * enum constant names case-insensitively (so `medium` accepts
 * `ReasoningEffort.MEDIUM`).
 *
 * Blank input, fail-fast on unknown values naming the accepted names,
 * and echoing the raw value in the error message all follow
 * [parseDurationConfig].
 * */
inline fun <reified T : Enum<T>> parseEnumConfig(name: String, raw: String?): T? {
    if (raw.isNullOrBlank()) return null
    val trimmed = raw.trim()
    return enumEntries<T>().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        ?: throw IllegalArgumentException(
            "Config `$name` has an invalid value: `$raw`, " +
                    "expected one of: ${enumEntries<T>().joinToString { it.name.lowercase() }}"
        )
}

/**
 * Parse a JSON object from a raw config value, for settings that carry
 * a provider-specific JSON body (for example the LLM request `extra`).
 *
 * Blank input, fail-fast, and echoing the raw value in the error
 * message all follow [parseDurationConfig]; a well-formed JSON value
 * that is not an object (array, string, number) is rejected too, so
 * the caller always gets a [JsonObject].
 * */
fun parseJsonObjectConfig(name: String, raw: String?): JsonObject? {
    if (raw.isNullOrBlank()) return null
    val element = try {
        Json.parseToJsonElement(raw.trim())
    } catch (e: SerializationException) {
        throw IllegalArgumentException("Config `$name` has an invalid JSON value: `$raw`", e)
    }
    return element as? JsonObject
        ?: throw IllegalArgumentException("Config `$name` must be a JSON object, got: `$raw`")
}
