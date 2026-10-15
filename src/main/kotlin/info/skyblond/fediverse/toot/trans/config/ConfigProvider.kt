package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.masto.RateLimitBackoff
import info.skyblond.fediverse.toot.trans.masto.statuses.StatusesPool
import kotlin.time.Duration

/**
 * Provide configuration.
 *
 * Each provider should only report configs from its own source.
 * When not configured, return null so the [ConfigStore] can fallback.
 * */
interface ConfigProvider {
    /**
     * Host of the instance, for example: `https://m.skyblond.info/`
     * */
    val mastoHost: String?

    /**
     * Your access token.
     * */
    val accessToken: String?

    /**
     * Miss collection window of the [StatusesPool], see its KDoc.
     * */
    val poolBatchWindow: Duration?

    /**
     * Insertion-based expiry of the [StatusesPool] cache, see its KDoc.
     * */
    val poolCacheTtl: Duration?

    /**
     * Entry limit of the [StatusesPool] LRU cache, see its KDoc.
     * */
    val poolMaxCacheEntries: Int?

    /**
     * Extra wait after a 429 reset, see [RateLimitBackoff].
     * */
    val backoffResetMargin: Duration?

    /**
     * Fixed backoff when a 429 reset header is unusable, see [RateLimitBackoff].
     * */
    val backoffFallbackDelay: Duration?

    /**
     * Sanity bound on reset-based backoff, see [RateLimitBackoff].
     * */
    val backoffMaxWaitDuration: Duration?

    /**
     * Amount of the owner's latest toots the boot-up scan fetches;
     * 0 disables the scan. The scan semantics live with the scan
     * itself: see `performInitialScan` in `Scan.kt`.
     * */
    val initialScanSize: Int?

    /**
     * Retry budget per page for transient failures (5xx, transport)
     * during the boot-up scan; 0 disables such retries. The retry
     * policy lives with the scan itself: see `performInitialScan`
     * in `Scan.kt`.
     * */
    val scanTransientMaxRetry: Int?

    /**
     * Spacing between the boot-up scan's transient retries, see
     * `performInitialScan` in `Scan.kt`.
     * */
    val scanTransientRetryDelay: Duration?

    /**
     * Target language for translation in plain text.
     * For example, "English" or "Simplified Chinese".
     * */
    val targetLanguage: String?

    /**
     * Target language code for translation, sent to the instance as-is
     * when posting the translated replies. The instance validates the
     * tag case-sensitively against its supported locale list, so what
     * it stores may differ from what was posted: tags on the list
     * round-trip unchanged (`en`, `zh-CN`, `zh-TW`); anything else
     * whose base tag is on the list is reduced to that base (`en-US`
     * and even `en-GB` become `en`, `zh-cn` becomes `zh`); anything
     * else makes the instance silently fall back to the account's
     * posting language — the exact rules live server-side, in
     * Mastodon's `PostStatusService` and `valid_locale_or_nil` from
     * `languages_helper.rb`. Fetched statuses are matched against this
     * config exactly (see `Main.kt`), so the value must be a tag the
     * instance stores as-is.
     * For example, "en", "zh", "zh-CN".
     * */
    val targetLangCode: String?
}
