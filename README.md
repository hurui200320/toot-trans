# toot-trans

Automatically translate toots.

## Compatibility

This project targets the account owner's locally authored posts on vanilla
Mastodon. Compatibility with customized forks is not guaranteed. Footer HTML
parsing assumptions are documented in `matchFooterFromContent` in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/llm/Footer.kt`.

Also this project requires your own toots have the correct language tag.
For example, if you post zh-CN and want to translate into en, but all your toots
are tagged en for some reason, then this project will ignore those toots,
as to the code, they are already in English.

## Current implementation

The application currently performs one startup scan, selects eligible
originals, and reconstructs each original's existing translation chain from
the scanned statuses (see `masto/statuses/TranslationChain.kt`).

> Mastodon lists the owner's statuses newest-first by status ID (creation
> order; edits do not reorder the listing). A translation reply is always
> created after its original, so whenever the original is inside the scan
> window, its segments are too: the chain can always be reconstructed from
> the fetch result.

LLM translation, posting, and periodic polling are not implemented yet: the
OpenAI-compatible chat client is implemented (see [LLM client](#llm-client)),
but nothing calls it until the translation stage lands.
Invalid translation chains are already cleaned up: their obsolete segments
are deleted (see `masto/statuses/StatusesDeleter.kt`), but the replacement
translation is not posted yet.

The chain rules — what counts as a complete chain, why stale chains are
accepted (one-shot), the language scoping for deployments sharing one
account, and the leaf-first deletion contract for invalid chains — are
documented on `findTranslationChainState` in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/TranslationChain.kt`.
The filter only decides eligibility; see `filter/TootFilter.kt`.

### Known limitations (current stage)

- Invalid chains (for example a half-posted `#1#3` of a 3-segment
  translation, or a valid chain with a leftover segment) are detected and
  their segments are deleted before retranslation, but the retranslation
  itself needs the translation and posting stage: nothing is posted yet.
- A translated original is never refreshed: the one-shot policy accepts a
  complete chain even when the original was edited afterwards. The footer
  hash lets a reader detect the mismatch; this project ships no tooling for
  that yet.
- Segments made unreachable by a parent deleted outside this application
  are not discovered and stay on the instance; clean up manually.

## Access Token

This project reuses the existing account so your followers don't need to follow a new account.
This also means you need to provide an access token via `Preferences` -\> `Development` -\> `New Application`.

For name, it's recommended to use `toot-trans-<target-lang-code>`, for example: `toot-trans-en`.
This allows you to have multiple instances deployed for different target languages,
each having its own access token instead of sharing one (which is a BAD pattern).

For redirect URI, use `urn:ietf:wg:oauth:2.0:oob`.

For permission, ***DO NOT BE LAZY AND GIVE EVERYTHING***. This project only needs the following permissions:

+ `profile`: we need to know your account info, so we can filter the toots for translation.
+ `read:statuses`: we need to read your toots for translation, and yes, they call toots `statuses`.
+ `write:statuses`: we need to post (aka write) translated toots.

Once you hit the submit button and create the application, click into it and you should see the access token.
It's **access token**, not application id or application secret. We're not using OAuth.
We just need the access token.

## Configuration

Configs are resolved from multiple sources. For each setting, sources are checked in this
order, and the first one providing a non-`null` value wins:

1. Environment variables
2. Properties file, default path `./config.properties`,
   changeable via the `CONFIG_PROPERTIES_PATH` environment variable
3. Built-in defaults (safe default values)

The properties file is read only once at startup, as UTF-8.
The default file name is gitignored, so you won't commit your token by accident.

If a required setting is not provided by any source, the app fails to start with a
`MissingConfigException` (fail-fast), instead of running with a half-empty config.

| Setting | Environment variable | Properties key | Required | Example |
|---|---|---|---|---|
| Instance host | `MASTO_HOST` | `mastoHost` | Yes | `https://mastodon.social/` |
| Access token | `ACCESS_TOKEN` | `accessToken` | Yes | the token you created above |
| Pool batch window | `POOL_BATCH_WINDOW` | `poolBatchWindow` | No, default `1s` | `500ms` |
| Pool cache TTL | `POOL_CACHE_TTL` | `poolCacheTtl` | No, default `1m` | `5m` |
| Pool max cache entries | `POOL_MAX_CACHE_ENTRIES` | `poolMaxCacheEntries` | No, default `1000` | `500` |
| Backoff reset margin | `BACKOFF_RESET_MARGIN` | `backoffResetMargin` | No, default `800ms` | `1s` |
| Backoff fallback delay | `BACKOFF_FALLBACK_DELAY` | `backoffFallbackDelay` | No, default `10s` | `30s` |
| Backoff max wait duration | `BACKOFF_MAX_WAIT_DURATION` | `backoffMaxWaitDuration` | No, default `24h` | `1h` |
| Initial scan size | `INITIAL_SCAN_SIZE` | `initialScanSize` | No, default `40` | `20` |
| Scan transient max retry | `SCAN_TRANSIENT_MAX_RETRY` | `scanTransientMaxRetry` | No, default `10` | `5` |
| Scan transient retry delay | `SCAN_TRANSIENT_RETRY_DELAY` | `scanTransientRetryDelay` | No, default `10s` | `30s` |
| Translation cooldown | `TRANSLATION_COOLDOWN` | `translationCooldown` | No, default `3m`; `0s` disables | `1m` |
| Target language | `TARGET_LANGUAGE` | `targetLanguage` | Yes | `English` |
| Target lang code | `TARGET_LANG_CODE` | `targetLangCode` | Yes | `en` |

The `pool*` settings tune `StatusesPool`; their batching and caching semantics
are documented in its KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/StatusesPool.kt`,
and the `backoff*` ones tune
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/RateLimitBackoff.kt` —
the 429 backoff policy shared by the pool's worker, the boot-up scan, and
status deletion, so they all honor these settings.
The `initialScanSize` setting controls how many of the owner's latest toots
the boot-up scan fetches and feeds into the pool, and the `scanTransient*`
ones bound that scan's retries of transient failures (5xx and transport
errors; 429 backoff is governed by the `backoff*` settings instead), see
`src/main/kotlin/info/skyblond/fediverse/toot/trans/Scan.kt`.
The `target*` pair picks the translation output: `targetLanguage` is the
plain-text language name (e.g. `English`, `Simplified Chinese`), and
`targetLangCode` is the language tag (e.g. `en`, `zh-CN`) used to tag the
posted translations; both are required, so each deployment decides its
target language at startup. How the instance validates and normalizes the
tag — which tags round-trip unchanged, and why the configured value must
be one of them for the bot to recognize its own replies — is documented
on `targetLangCode`'s KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/config/ConfigProvider.kt`.
Duration values accept units like `500ms`, `1s`, `2m`, `24h`, as well as
ISO-8601 durations like `PT24H`. An empty value is treated as unset; a
malformed or out-of-range value fails startup with an error naming the
setting (durations must be at least `1ms`, except `backoffResetMargin` which
allows `0s` and `translationCooldown` which is finite and non-negative;
`poolMaxCacheEntries` must be positive; `initialScanSize` and
`scanTransientMaxRetry` must be non-negative, where `0` disables the boot-up
scan and transient retries respectively).

The eligibility policy — the cooldown and the translated-ancestry
prerequisite — is documented on `filter/TootFilter.kt`.
`initialScanSize` limits the number of recent posts, not their age; there is
no separate time cutoff.

Example `config.properties`:

```properties
mastoHost=https://mastodon.social/
accessToken=your-access-token
targetLanguage=English
targetLangCode=en
```

## Shared status lookups

Use `StatusesPool` for status-by-ID reads instead of calling the HTTP client
directly. Its KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/StatusesPool.kt`
documents cache, batching, retry, and lifecycle settings. The `pool*` configs
in the table above override those settings at deploy time.

```kotlin
val pool = StatusesPool(statusesClient, applicationScope)
try {
    pool.seed(statusesFromAccountPolling)
    val parent = pool.getStatus(parentId)
    val refreshed = pool.getStatus(parentId, bypassCache = true)
    // After successfully deleting a status:
    pool.invalidate(deletedId)
} finally {
    pool.close()
}
```

In the application, Koin owns the pool and calls `close()` during teardown,
on normal exit, or JVM shutdown (including Docker's SIGTERM). Closing cancels
pending lookups and waits for worker cleanup; it does not drain requests.

Lookups return `null` for statuses unavailable to the current token.
Cache bypass still obeys the pool's batching and rate-limit handling.
Account polling, status-source reads, and posting remain outside the pool.
Deletion goes through `masto/statuses/StatusesDeleter.kt` instead of
calling the HTTP client directly: it deletes leaf-first, honors the shared
429 backoff, and calls `pool.invalidate` after every successful DELETE.
The startup scan handles its own rate limits using the shared backoff
policy; future posting flows must do the same. The filter does not invoke
deletion.

## LLM client

Translation talks to models through the OpenAI-compatible chat client in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/llm/client/` (`ChatClient`,
`OkHttpChatClient`, `RetryingChatClient`). The client is deliberately
provider-agnostic and aims to support as many OpenAI-compatible providers as we
can — OpenAI itself, llama.cpp's server, DeepInfra, synthetic.new, Novita,
ZenMux, Gemini's OpenAI compatibility layer, and any other gateway that speaks
`POST /chat/completions`. Pointing it at a provider is just an API root (a path
such as `/v1` is expected) and an API key. The transport builds no HTTP client of
its own: the caller injects one, built with the recommended timeouts on
`OkHttpChatClient` (`RECOMMENDED_READ_TIMEOUT` / `RECOMMENDED_WRITE_TIMEOUT`
because generation can run for minutes, `RECOMMENDED_CONNECT_TIMEOUT` so an
unreachable endpoint fails fast).

A summary of the behavior; the KDoc in the files above is the authoritative
reference, and the provider-specific notes live on `ChatCompletionRequest.extra`:

- **Chat Completions, not the Responses API.** The conversation is only the
  caller-supplied message list — no server-side conversation objects to create or
  clean up — which matches the project's no-state design. The full rationale and
  the provider-compatibility argument are documented on `ChatClient`.
- **Non-streaming only.** `stream` is pinned to `false` on the wire; answers are
  always complete objects, never partial deltas.
- **Multi-turn history and images.** Previous turns are caller-supplied
  `system` / `user` / `assistant` messages; images are base64 `data:` URLs because
  the caller downloads the media (providers never fetch URLs on our behalf, and
  private or followers-only toot media would not be reachable for them anyway).
  Base64 inflates image payloads by about a third and every retry resends the whole
  body, so callers must bound the number and size of images.
- **Minimal standard parameters, everything else via `extra`.** Standard knobs are
  `model`, `messages`, `max_completion_tokens` (covers thinking tokens too) and
  `reasoning_effort` (thinking control; some models cannot disable thinking).
  Anything provider-specific — `temperature` and other sampling parameters, plus
  each provider's own thinking switches — goes through `extra`, which is merged
  into the top-level request body; keys the client owns (`model`, `messages`,
  `stream`, ...) are rejected. The provider-specific notes live on
  `ChatCompletionRequest.extra`. Thinking output is deliberately never parsed:
  only the final answer is used.
- **Retry policy.** Connection failures and transient provider answers
  (408, 429, and all 5xx) are retried on a fixed delay with a bounded
  attempt budget (see `RetryingChatClient` for the exact policy); invalid requests,
  rejected keys, 402s and 403s, and malformed responses fail immediately, and a
  failed translation is simply retried by the next polling cycle.

The client is not wired into the application yet: nothing calls it until the
translation stage is implemented.
