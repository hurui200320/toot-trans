package info.skyblond.fediverse.toot.trans.config

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
