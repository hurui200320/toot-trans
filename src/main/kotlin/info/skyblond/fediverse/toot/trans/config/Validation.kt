package info.skyblond.fediverse.toot.trans.config

import kotlin.time.Duration

/**
 * Shared predicate of the transient retry contract: a retry delay must
 * be finite and at least 1ms, so a retry loop always waits a real
 * amount of time. Every consumer of the `transient*` settings — the
 * boot-up scan (`performInitialScan` in `Scan.kt`), the LLM retry
 * wrapper (`RetryingChatClient`) and the DI module — runs its value
 * through this one predicate, so the rule cannot drift apart; [name]
 * makes each error point at the layer that supplied the value (the
 * config setting at the config boundary, the parameter name inside a
 * component).
 * */
fun requireValidRetryDelay(name: String, value: Duration): Duration {
    require(value.isFinite() && value.inWholeMilliseconds > 0) {
        "$name must be finite and at least 1ms, got $value"
    }
    return value
}

/**
 * Shared predicate of the transient retry contract: the retry budget
 * must be non-negative, where 0 disables retries. Same consumers and
 * [name] convention as [requireValidRetryDelay]; the DI module
 * additionally bounds the value away from Int.MAX_VALUE because of
 * its +1 attempt-budget conversion, see `di/AppModule.kt`.
 * */
fun requireValidTransientMaxRetry(name: String, value: Int): Int {
    require(value >= 0) { "$name must be non-negative, got $value" }
    return value
}

/**
 * Shared predicate of the translation cooldown contract: the cooldown
 * must be finite and non-negative, where 0 disables it (except for
 * future timestamps, see `Cooldown` in
 * `masto/statuses/Cooldown.kt`). Consumers: `Cooldown`'s constructor,
 * so a programmatic value fails at the component, and `di/AppModule.kt`,
 * so a config value fails naming the setting.
 * */
fun requireValidCooldown(name: String, value: Duration): Duration {
    require(value.isFinite() && !value.isNegative()) {
        "$name must be finite and non-negative, got $value"
    }
    return value
}

/**
 * Shared predicate of the translation max-age contract: the cutoff must
 * be finite and non-negative, where 0 disables it (see `PendingQueue` in
 * `masto/statuses/PendingQueue.kt`). Consumers: `PendingQueue`'s
 * constructor, so a programmatic value fails at the component, and
 * `di/AppModule.kt`, so a config value fails naming the setting.
 * */
fun requireValidTranslationMaxAge(name: String, value: Duration): Duration {
    require(value.isFinite() && !value.isNegative()) {
        "$name must be finite and non-negative, got $value"
    }
    return value
}

/**
 * Shared predicate of the reply-chain depth contract: the depth must
 * be non-negative, where 0 disables the LLM history. Consumers:
 * `ReplyChainContext`'s constructor, so a programmatic value fails at
 * the component, and `di/AppModule.kt`, so a config value fails naming
 * the setting.
 * */
fun requireValidReplyChainDepth(name: String, value: Int): Int {
    require(value >= 0) { "$name must be non-negative, got $value" }
    return value
}

/**
 * Shared predicate of the max-completion-tokens contract: the token
 * budget must be positive, because the chat request model rejects zero
 * and negative values. Consumers: `TranslationRequestFactory`'s
 * constructor, so a programmatic value fails at the component, and
 * `di/AppModule.kt`, so a config value fails naming the setting.
 * */
fun requireValidMaxCompletionTokens(name: String, value: Int): Int {
    require(value > 0) { "$name must be positive, got $value" }
    return value
}
