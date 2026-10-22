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

The application performs one startup scan, feeds the pending queue
(`masto/statuses/PendingQueue.kt`), drains it through the translation stage
(`TranslationProcessor.kt`), then loops: wait `scanInterval`, re-scan from the
rolling cursor to the latest toots, feed the queue, and drain again. A drain
ends either cleared or blocked (a toot still cooling down, or a failure that
stops the drain); both outcomes wait out the interval and continue with the
next scan — a blocked drain is not terminal. The queue is the work window:
`feed` runs the entrance filter, `next` hands out the oldest entry, and `drop`
removes a processed one. Dropping the queue head records it as the rolling
cursor, and the incremental scan resumes right after it (`min_id` paging, see
`Scan.kt`); entries still inside the cooldown stay queued and are refreshed by
the next scan, so the cursor never skips something unprocessed. Ordering is by
creation time, which keeps every parent ahead of its replies. While no cursor
exists yet (nothing was ever dropped), the loop repeats the bounded startup
scan; once a drain leaves entries queued without a cursor, the next scan drops
that queue head to establish the cursor
(`PendingQueue.dropHeadAndRecordCursor`), because the bounded window cannot
guarantee it still covers that head as new toots arrive.

> Mastodon lists the owner's statuses newest-first by status ID (creation
> order; edits do not reorder the listing). A translation reply is always
> created after its original, so whenever the original is inside the scan
> window, its segments are too: the chain can always be reconstructed from
> the fetch result.

The drain, per eligible toot:

1. Reconstruct the existing translation chain and delete obsolete segments
   leaf-first when there is any (`masto/statuses/TranslationChain.kt`,
   `masto/statuses/StatusesDeleter.kt`). A toot whose chain is already complete
   is dropped untouched (one-shot).
2. Build the LLM context (`masto/statuses/ReplyChainContext.kt`): up to
   `replyChainDepth` ancestors through the status pool, every entry's composing
   text via `/statuses/:id/source`, each translated ancestor as a user/assistant
   turn (`<missing translation>` for an ancestor without a complete chain,
   `<empty content>` for a toot with neither body nor warning), and the toot's
   own images (bounded, failures skipped) for the vision input. A toot that
   quotes another one (an accepted quote) also contributes that toot's rendered
   HTML content and warning, embedded in the quote entity itself, as a
   reference-only `context` block next to the link preview card and media
   descriptions. A toot turn is a JSON object with a `content` and a
   `spoiler_text` field, either null when the toot does not have that part.
3. Translate (`llm/Translator.kt`): the request fallback sequence is full
   (history + context + images) → text-only → minimal; a payload-shaped 4xx
   downgrades to the next variant, while an unusable answer (unparsable,
   against the source shape, or without usable text) is retried three times
   on the same variant first — a flaky answer thus never shrinks the request —
   and only then downgrades. A content-filtered answer is the exception: the
   provider refuses that payload deterministically, so it downgrades right
   away. The answer is one JSON
   object `{"content": ..., "spoiler_text": ...}` (`llm/TranslationOutput.kt`),
   constrained by a strict JSON schema sent as `response_format` on every
   request — structured outputs are a requirement, so a provider or model
   without them cannot be used — with a null field mirroring a field the toot
   does not have: a content-warning-only toot answers a null content, and an
   answer that still drops a field the source has, at the end of the whole
   fallback sequence, fails that toot.
4. Post (`llm/TranslationChunks.kt`, `masto/statuses/TranslationPoster.kt`):
   split the answer into footer-carrying segments (paragraph → sentence → hard
   cut, surrogate pairs never halved, the instance's 500-character budget with
   the footer and the content warning reserved on every segment) and post them
   as one connected reply chain: the first segment replies to the original,
   every following one to the previous segment. The target language code tags
   every segment, the translated content warning rides on the first segment
   (with `sensitive` copied from the original), a `public` original yields
   `unlisted` replies while any other visibility is kept (a `direct` reply
   cannot carry the original's mentions and stays visible to this account
   only, see the known limitations), and the original's id feeds the
   `Idempotency-Key` of every segment, so a retried post cannot duplicate.
   A body-less translation (a content-warning-only source) posts the footer
   alone with the translated warning, since the instance rejects a status
   that has no text at all.

A 429 waits out the instance's rate-limit reset through the shared backoff
policy and retries the same segment; 5xx responses and transport errors retry
within the `transient*` budget; a partial chain left by a failure is cleaned
up by the next drain cycle (see below).

Every finished translation is also logged as one block per toot, framed by
markers, carrying the source, both content warnings and the answer (see
`TranslationProcessor.kt`), so the prompt and the result can be reviewed
straight from the log — including when the instance later rejects the post;
`TranslationPoster` logs each posted segment.

Failure policy (see `TranslationProcessor`): toot-specific failures — an answer
that stays unusable through the translator's retries and variant downgrades,
a permanently rejected payload after the fallback sequence, a chain status
deleted outside the application (a source read answering 404), or an instance
permanently rejecting the reply payload (400/422, e.g. a custom character
limit) — drop the toot with a loud log so the queue keeps moving; rate limits,
server errors and transport errors stop the drain and leave everything queued
for the next scan cycle, while a posting failure also leaves the already-posted
prefix in place for the next cycle's chain check to delete leaf-first and
replace. The one exception to that retry rule is the no-cursor bootstrap
described above: the loop drops the blocked head to establish the cursor,
logging it, so that entry is not retried. Credential/configuration failures —
an LLM 4xx outside the payload set, or the instance rejecting our token or
permissions with 401/403 — propagate, because no following toot could succeed
either; the process then exits (fail-fast), and a restart rebuilds the window
from a fresh boot scan.

The chain rules — what counts as a complete chain, why stale chains are
accepted (one-shot), the language scoping for deployments sharing one
account, and the leaf-first deletion contract for invalid chains — are
documented on `findTranslationChainState` in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/TranslationChain.kt`.
The queue only decides what enters the work window; see
`PendingQueue.feed` in `src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/PendingQueue.kt`.

### Known limitations (current stage)

- The instance's actual per-toot character limit is not read yet (a future
  `GET /api/v2/instance` milestone): segments are sized against the default
  500, so an instance with a smaller limit permanently rejects its
  translations (the toot is skipped with a loud log while it stays in the
  scan window).
- A `direct` original's translation is a `direct` reply without the original's
  mentions, so the accounts the original was addressed to cannot see it:
  Mastodon scopes direct statuses to the author and the mentioned accounts,
  and the reply copies no mentions. The translation stays visible to this
  account only.
- A translated original is never refreshed: the one-shot policy accepts a
  complete chain even when the original was edited afterwards. The footer
  hash lets a reader detect the mismatch; this project ships no tooling for
  that yet. The same applies to an original whose id is at or below the
  rolling cursor: an edit does not change the id, so the incremental scan
  never sees it again.
- Segments made unreachable by a parent deleted outside this application
  are not discovered and stay on the instance; clean up manually.
- A permanently rejected toot (content filter, a model without the needed
  capabilities even after the fallback sequence, or an instance rejecting the
  reply payload) is dropped from the window with a loud log; because it is the
  window head, its drop advances the rolling cursor past it, so it is not
  retried automatically. A provider that rejects everything keeps producing
  loud logs for the toots that follow.

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

If a required setting is not provided by any source, the app fails with a
`MissingConfigException` (fail-fast) instead of running with a half-empty
config: at startup for the settings the boot path reads (the instance
connection, the target language, the LLM transport — `llmBaseUrl`,
`llmApiKey` — and the translation stage's `llmModel`,
`llmMaxCompletionTokens`, which the stage resolves while the container is
built).

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
| Scan interval | `SCAN_INTERVAL` | `scanInterval` | No, default `1m` | `30s` |
| Transient max retry | `TRANSIENT_MAX_RETRY` | `transientMaxRetry` | No, default `10` | `5` |
| Transient retry delay | `TRANSIENT_RETRY_DELAY` | `transientRetryDelay` | No, default `10s` | `30s` |
| Translation cooldown | `TRANSLATION_COOLDOWN` | `translationCooldown` | No, default `3m`; `0s` disables | `1m` |
| Translation max age | `TRANSLATION_MAX_AGE` | `translationMaxAge` | No, default `1d`; `0s` disables | `12h` |
| Reply chain depth | `REPLY_CHAIN_DEPTH` | `replyChainDepth` | No, default `3`; `0` disables context | `5` |
| Target language | `TARGET_LANGUAGE` | `targetLanguage` | Yes | `English` |
| Target lang code | `TARGET_LANG_CODE` | `targetLangCode` | Yes | `en` |
| LLM base URL | `LLM_BASE_URL` | `llmBaseUrl` | Yes | `https://api.openai.com/v1` |
| LLM API key | `LLM_API_KEY` | `llmApiKey` | Yes | your provider's key |
| LLM connect timeout | `LLM_CONNECT_TIMEOUT` | `llmConnectTimeout` | No, default `30s`; `0s` disables | `10s` |
| LLM read timeout | `LLM_READ_TIMEOUT` | `llmReadTimeout` | No, default `10m`; `0s` disables | `5m` |
| LLM write timeout | `LLM_WRITE_TIMEOUT` | `llmWriteTimeout` | No, default `2m`; `0s` disables | `1m` |
| LLM model | `LLM_MODEL` | `llmModel` | Yes | `gpt-5-mini` |
| LLM max completion tokens | `LLM_MAX_COMPLETION_TOKENS` | `llmMaxCompletionTokens` | Yes | `4096` |
| LLM reasoning effort | `LLM_REASONING_EFFORT` | `llmReasoningEffort` | No, unset omits the field | `medium` |
| LLM request extra | `LLM_REQ_EXTRA` | `llmReqExtra` | No, default `{}` | `{"temperature": 0.2}` |

The `pool*` settings tune `StatusesCachePool`; their batching and caching semantics
are documented in its KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/StatusesCachePool.kt`,
and the `backoff*` ones tune
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/RateLimitBackoff.kt` —
the 429 backoff policy shared by the pool's worker, the scans, and
status deletion, so they all honor these settings.
The `initialScanSize` setting controls how many of the owner's latest toots
the newest-window scan fetches (at boot, and in the loop while no cursor exists
yet); the pending queue seeds the pool from the scan result and performs the
entrance filter (see `PendingQueue.feed`).
The `scanInterval` setting paces the loop: after each drain — cleared or
blocked — it waits that long, then scans from the rolling cursor to the latest
toots (`performIncrementalScan` in `Scan.kt`, `min_id` paging), feeds them and
drains again (or repeats the bounded newest-window scan while no cursor exists
yet). The wait has no disabled mode: one scan per minute is a few
percent of the per-account paging bucket (300 requests / 15 minutes, see
`AGENTS.md`), while a much smaller value mostly produces 429s that the shared
backoff has to wait out. A catch-up after downtime pages through everything
newer than the cursor, paced by that same backoff; `translationMaxAge` decides
which of those toots still enter the work window.
The `transient*` settings bound transient-failure retries globally (5xx and
transport errors; 429 backoff is governed by the `backoff*` settings
instead): the scans use them per page, and the LLM client's retry
wrapper derives its attempt budget and fixed delay from them, see
`src/main/kotlin/info/skyblond/fediverse/toot/trans/Scan.kt`; the previous
`scanTransient*` names are gone. For the LLM client this means one completion
can spend up to `transientMaxRetry + 1` attempts (the initial attempt plus
that many retries), each attempt itself bounded by `llmReadTimeout`; the
request walk can multiply that by its three variants (full/text/minimal) and by
four answer attempts per variant (the initial completion plus three retries
on an unusable answer) — so the budget multiplies the worst-case time a single
translation can wait before the next drain cycle sees its failure.
The `target*` pair picks the translation output: `targetLanguage` is the
plain-text language name (e.g. `English`, `Simplified Chinese`), and
`targetLangCode` is the language tag (e.g. `en`, `zh-CN`) used to tag the
posted translations; both are required, so each deployment decides its
target language at startup. How the instance validates and normalizes the
tag — which tags round-trip unchanged, and why the configured value must
be one of them for the bot to recognize its own replies — is documented
on `targetLangCode`'s KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/config/ConfigProvider.kt`.
The `llm*` settings configure the OpenAI-compatible chat client.
`llmBaseUrl` and `llmApiKey` point it at a provider and are checked at
startup: the base URL is the API root (a path such as `/v1` is expected; the
transport appends the endpoint), and the API key is a secret and is never
logged. The `llm*` timeouts apply to the LLM transport only — it is derived
from the shared OkHttp client, so connections and threads are still pooled
together — and default to the transport's recommended values
(`30s` / `10m` / `2m`): generation is slow, while the instance API keeps
OkHttp's defaults. `llmModel`, `llmMaxCompletionTokens`,
`llmReasoningEffort` and `llmReqExtra` shape every
translation request: the
reasoning effort accepts the
enum names (`none`, `minimal`, `low`, `medium`, `high`, `xhigh`, `max`,
matched case-insensitively) and is optional — when unset, the field is
omitted from the request, so providers that reject `reasoning_effort` are
targeted by leaving it unset — and `llmReqExtra` must be a JSON object
(defaulting to `{}` when unset) that does not name any field the client
owns (see `ChatCompletionRequest.extra`; `response_format` is client-owned
because the stage always sends the strict schema on it). Structured
outputs are a requirement, not a preference: every translation request
carries the strict JSON schema as its `response_format`, so a provider or
model that does not support it cannot serve this project — there is no
fallback to JSON mode or free text.
Duration values accept units like `500ms`, `1s`, `2m`, `24h`, as well as
ISO-8601 durations like `PT24H`. An empty value is treated as unset; a
malformed or out-of-range value fails with an error naming the setting
(durations must be at least `1ms`, except `backoffResetMargin` which allows
`0s`, `translationCooldown` and `translationMaxAge` which are finite and
non-negative, and the `llm*`
timeouts which are finite and non-negative, where `0` disables the timeout;
`scanInterval` is in the at-least-`1ms` group and cannot be disabled — the
wait is the point; `poolMaxCacheEntries` and `llmMaxCompletionTokens` must be
positive;
`initialScanSize`, `transientMaxRetry`
and `replyChainDepth` must be non-negative, where `0` disables the newest-window
scan, transient retries and the LLM context respectively).

The eligibility policy — the footered-reply/language/blank-body checks,
the max-age cutoff, the full reply-chain qualification, and the
creation-time ordering — is documented on `PendingQueue.feed`, and the
max-age timing (same effective time, same fetch-time check) on the
`PendingQueue` class KDoc, both in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/PendingQueue.kt`;
the cooldown timing (`editedAt ?: createdAt`, checked against the fetch
time) on `Cooldown` in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/Cooldown.kt`.
`initialScanSize` limits the number of recent posts, `translationMaxAge` the
age of what gets translated: toots already older than the cutoff when
fetched are skipped, while the scan window stays as large as configured
(the skipped toots still feed the chain context of younger ones, and
translated segments are never subject to the cutoff, so a younger reply to
an old toot keeps its history). A fresh boot or a catch-up after downtime
therefore cannot suddenly translate and reply to toots from days ago; on
the other hand, downtime longer than the cutoff leaves those toots
untranslated, so raise the setting to widen the catch-up window.
`replyChainDepth` bounds how many ancestors feed the
LLM context; the context rules (pool reads, source fetches, translated-ancestor
turns, media) live on `ReplyChainContext` in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/ReplyChainContext.kt`.

Example `config.properties`:

```properties
mastoHost=https://mastodon.social/
accessToken=your-access-token
targetLanguage=English
targetLangCode=en

# LLM provider settings: the base URL and key are needed at startup,
# the rest shape every translation request
# llmBaseUrl=https://api.openai.com/v1
# llmApiKey=your-provider-key
# llmModel=gpt-5-mini
# llmMaxCompletionTokens=4096
# llmReasoningEffort=medium
# llmReqExtra={}
# replyChainDepth=3
# translationMaxAge=1d
# scanInterval=1m
```

### Proxy

HTTP traffic — the instance API, media downloads and the LLM transport
alike — honors the conventional proxy environment variables: the first
non-blank of `HTTPS_PROXY`, `https_proxy`, `HTTP_PROXY`, `http_proxy`
wins (the uppercase spelling of each pair beats the lowercase one). There
is no per-scheme selection: the value is a plain-HTTP proxy and HTTPS
targets are tunneled through it with `CONNECT`, so an HTTPS-only proxy
setup needs only `HTTPS_PROXY`; set no variable to connect directly.

The value must be a proxy URL such as `http://127.0.0.1:3128`. A
malformed value, an `https://` URL (OkHttp connects to proxies in
plaintext, it cannot speak TLS to one) and a URL carrying credentials
(proxy authentication is not supported) all fail boot, naming the
variable — a broken proxy setup never silently degrades to a direct
connection. `NO_PROXY` is not honored: once a proxy is configured, every
request uses it.

## Shared status lookups

Use `StatusesCachePool` for status-by-ID reads instead of calling the HTTP client
directly. Its KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/StatusesCachePool.kt`
documents cache, batching, retry, and lifecycle settings. The `pool*` configs
in the table above override those settings at deploy time.

```kotlin
val pool = StatusesCachePool(statusesClient, applicationScope)
try {
    pool.seed(fetchedFromAccountPolling) // FetchedStatus values from polling/scan
    val parent = pool.getStatus(parentId) // FetchedStatus?
    val refreshed = pool.getStatus(parentId, bypassCache = true)
    val snapshot = pool.dump() // every cached FetchedStatus
    // After successfully deleting a status:
    pool.invalidate(deletedId)
} finally {
    pool.close()
}
```

In the application, Koin owns the pool and calls `close()` during teardown,
on normal exit, or JVM shutdown (including Docker's SIGTERM). Closing cancels
pending lookups and waits for worker cleanup; it does not drain requests.

Lookups return `null` for statuses unavailable to the current token, and
every returned value is a `FetchedStatus` — the status plus the instant it
was fetched — so time-sensitive checks (e.g. the cooldown) run against the
observation time. Cache bypass still obeys the pool's batching and
rate-limit handling.
Account polling and status-source reads remain outside the pool.
Deletion goes through `masto/statuses/StatusesDeleter.kt` instead of
calling the HTTP client directly: it deletes leaf-first, honors the shared
429 backoff, and calls `pool.invalidate` plus `PendingQueue.drop` after
every successful DELETE. The entrance filter does not invoke deletion.

## LLM client

Translation talks to models through the OpenAI-compatible chat client in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/llm/client/` (`ChatClient`,
`OkHttpChatClient`, `RetryingChatClient`). The client is deliberately
provider-agnostic and aims to support as many OpenAI-compatible providers as we
can — OpenAI itself, llama.cpp's server, DeepInfra, synthetic.new, Novita,
ZenMux, Gemini's OpenAI compatibility layer, and any other gateway that speaks
`POST /chat/completions` (and supports structured outputs, a deployment
requirement — see the translation stage below). Pointing it at a provider is
just an API root (a path such as `/v1` is expected) and an API key. The
transport builds no HTTP client of its own: the caller injects one, built
with the recommended timeouts on
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
  failed translation is simply retried by the next drain cycle.

The client is wired into the application container: `ChatClient` resolves to
`RetryingChatClient` over `OkHttpChatClient`, built from the `llm*` settings
(see [Configuration](#configuration)) and resolved at startup, so a missing
or invalid LLM transport config fails boot.

The translation stage on top of the client is `llm/Translator.kt`: it tries the
request fallback sequence (full with images → text-only → minimal) and
downgrades on each payload-shaped 4xx (400, 413, 415, 422) before giving up.
An *answer-shaped* failure — an unparsable answer, one that breaks the source
shape, or a completion without usable text — is not a payload problem: the same
variant is attempted up to four times (the initial call plus three retries)
before the walk downgrades, and only once the whole fallback sequence is
exhausted does the processor see the failure and drop the toot. A
content-filtered answer is deterministic for the same payload: it downgrades
right away instead of spending the remaining attempts. The
prompt contract —
one JSON object `{"content": ..., "spoiler_text": ...}` with each field null for
a part the toot does not have, toot turns shaped the same way, the
`<missing translation>` and `<empty content>` placeholders and the preservation
rules — lives on `llm/TranslationRequestFactory.kt`, together with the strict
JSON schema sent as `response_format` on every request:
the answer is constrained at the wire level, not only by the prompt. Structured
outputs are required with no fallback (see the `llm*` notes in
[Configuration](#configuration)): a provider or model that cannot enforce the
schema cannot serve this project. Parsing — tolerant of an unwrapped answer or a
wrapper code fence — lives on `llm/TranslationOutput.kt`.
