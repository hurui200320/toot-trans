# AGENTS.md

Guidance for AI agents (and humans) working in this project.

> This file acts as the TOC and a map of the repo.
> Its purpose is to help humans and LLMs quickly locate related components without exploring the whole codebase.
> It MUST NOT repeat comments from the code or explain things that are obvious by just taking a look at the code.

## Contents

- [Project](#project)
- [Repo map](#repo-map)
- [Architecture](#architecture)
- [Mastodon rate limits](#mastodon-rate-limits)
- [Verification commands](#verification-commands)
- [Code quality and style rules](#code-quality-and-style-rules)
- [Operational rule](#operational-rule)

This file is a MAP, not a specification: for any behavioral question the
code (and its KDoc) is authoritative. README.md owns the run instructions,
the full config reference, and the API table — this file only points at
them. Where a fact lives in a file's KDoc, this map names the file instead
of restating the fact.

## Project

This project (toot-trans) is a toot translate tool for mastodon.
It will automatically translate toots posted by the account owner
into preconfigured language and post the translation as a reply to
the original toot.

The primary deployment artifact is a GraalVM (Community Edition) native
image: keep the app and its dependencies buildable by
`./gradlew nativeCompile` (see the README's "Native image (GraalVM CE)"
section for the build facts).

## Repo map

Base package for toot translation: `info.skyblond.fediverse.toot.trans` (avoid using Mastodon in code due to copyright issues).

- `masto/statuses/StatusesCachePool.kt`: shared cached/batched lookups,
  lifecycle, cache bypass, and rate-limit retry semantics.
- `masto/statuses/StatusesDeleter.kt`: leaf-first deletion of obsolete
  translation segments, 429 backoff, and pool/queue invalidation on success.
- `masto/statuses/PendingQueue.kt`: the translation work window: entrance
  eligibility (basic checks, the max-age cutoff, plus full reply-chain
  qualification), the creation-ordered queue, the rolling cursor, and the
  max-age timing contract.
- `masto/statuses/Cooldown.kt`: the fetched-at cooldown policy (effective
  time `editedAt ?: createdAt`); single-sources the default cooldown.
- `Scan.kt`: the bounded newest-window scan and the cursor-based
  incremental scan, sharing one page-retry policy.
- `di/AppModule.kt`: the Koin object graph started by `Main.kt`: config
  chain, instance API transport, statuses pool, pending queue, cooldown and
  max-age cutoff, the application coroutine scope, the LLM chat client and
  the translation stage.
- `di/ProxyFromEnv.kt`: the shared OkHttp client's proxy, resolved from
  the conventional environment variables (`HTTPS_PROXY` and friends).
- `masto/statuses/TranslationChain.kt`: in-memory reconstruction and
  classification of an original's translation chain.
- `masto/statuses/TranslationPoster.kt`: posting one translation as the
  original's reply chain: per-segment idempotency, the
  visibility/CW/sensitive rules and the 429/transient retry policy.
- `masto/statuses/ReplyChainContext.kt`: the LLM context builder: ancestor
  walk via the pool, source fetches, translated-ancestor resolution, the
  target's quoted-toot context and the bounded target media downloads.
- `TranslationProcessor.kt`: the queue drain and its failure policy: the
  orchestrator `Main.kt` runs after each scan.
- `masto/client/`: instance API wire layer over `MastoApiClient.kt`, split
  into `accounts/`, `statuses/` and `media/`: contracts, OkHttp transports
  and the wire models (including `FetchedStatus`'s fetch timestamp).
- `llm/client/ChatClient.kt`: stateless, non-streaming Chat Completions
  contract; entry point for the wire documentation.
- `llm/client/OkHttpChatClient.kt`: direct transport: endpoint derivation,
  error-body mapping and injected-client timeouts.
- `llm/client/RetryingChatClient.kt`: fixed-delay retry policy for transient
  failures; also single-sources `ChatApiException.isRetryable`.
- `llm/client/Models.kt`: Chat Completions wire models.
- `llm/Footer.kt`: the `🌐{hash}#{seq}#{total}` reply marker: rendering,
  matching and stripping.
- `llm/TranslationChunks.kt`: the segment character budget and the greedy
  paragraph → sentence → hard-cut segmentation that packs one translation
  into footer-carrying segments.
- `llm/TranslationRequestFactory.kt`: the prompt contract (JSON answer shape,
  history pairing, context blocks: quoted toot, link preview, media), the
  always-sent strict `response_format` schema and the three request variants.
- `llm/Translator.kt`: the request fallback sequence over `ChatClient` —
  payload-shaped downgrades and answer retries (a content-filtered answer
  skips the retries) — plus the answer's source-shape policy.
- `llm/TranslationOutput.kt`: parsing of the JSON answer contract.
- `llm/TranslationContext.kt`: the prompt input types (including the
  quoted-toot context) and the `<missing translation>` / `<empty content>`
  placeholders.

## Architecture

This project is built with no-state in mind.

To avoid slow updates of Mastodon API libraries, this project us OkHttp
to access API directly, parsing stuff we only need.

The object graph is wired with Koin in the `di` package:
`appModule(config)` builds it from the resolved config store, and
`Main.kt` starts and stops the container.

For LLM translation, this project accepts standard OpenAI compatible
chat completion API settings.

GraalVM native image is a first-class build target (`graalvmNative` in
`build.gradle.kts`): the code avoids runtime reflection beyond what the
dependencies need, every Koin binding in `di/AppModule.kt` is an
explicit lambda, and Logback's reflection metadata comes from the
GraalVM Reachability Metadata Repository (do not disable it). When
adding a dependency or reflection-like code, re-verify the native
build; the README's "Native image (GraalVM CE)" section owns the
details.

The translation stage is the queue drain `TranslationProcessor.kt` runs:
context (`masto/statuses/ReplyChainContext.kt`) → translate
(`llm/Translator.kt`) → log for review → post (`llm/TranslationChunks.kt` +
`masto/statuses/TranslationPoster.kt`). `Main.kt` runs the loop: boot scan →
drain → wait `scanInterval` → incremental scan from the rolling cursor (the
bounded newest-window scan while no cursor exists yet) → feed → drain again.
A blocked drain is not terminal; the next scan refreshes the queued copies
(see `PendingQueue.kt` for the cursor contract).

## Mastodon rate limits

Source of truth: https://docs.joinmastodon.org/api/rate-limits/ and
`config/initializers/rack_attack.rb` upstream. Values below are defaults;
instances may customize them — do not hardcode them as assumptions.

Limits are **shared buckets keyed by caller, not per-endpoint counters**:
every request consumes from all buckets it matches, and the first
exhausted bucket returns HTTP 429. This project's traffic touches:

| Bucket (default) | Scope | Consumed by |
| :--- | :--- | :--- |
| 300 req / 5 min | per access token | every API call |
| 1500 req / 5 min | per account | every API call (all tokens combined) |
| 300 req / 15 min | per account, paging | any request with `since_id`/`max_id`/`min_id`/`page` (our polling) |
| 30 req / 30 min | per account | `DELETE /statuses/:id` — cleanup of obsolete translation reply chains |

- Responses (including successful ones) carry `X-RateLimit-Limit` /
  `X-RateLimit-Remaining` / `X-RateLimit-Reset` for the bucket closest to
  exhaustion; windows are fixed wall-clock periods, not sliding.
- Steady-state budget is tiny (one poll/min ≈ 1.6% of the token bucket).
- `MastoApiClient.kt` exposes `X-RateLimit-Reset` on 429 in
  `MastoApiException.rateLimitReset`; `StatusesCachePool.kt`,
  `StatusesDeleter.kt` and `TranslationPoster.kt` consume it for pooled
  lookups, deletions and posting respectively. Other API traffic must
  handle its own rate limits.

## Verification commands

```bash
./gradlew test
./gradlew nativeCompile
```

`nativeCompile` needs `GRAALVM_HOME` pointing at a GraalVM for JDK 25
(see the README's "Native image (GraalVM CE)" section).

Run verification after any relevant source change. All commands must exit clean.

## Code quality and style rules

These sections describe the rules/items to watch out for when writing or
reviewing code. When writing or reviewing code, look for bugs from the
following perspectives:

- Bug detection and correctness: Logic errors, off-by-one mistakes, race conditions, unhandled edge cases, incorrect assumptions, regressions.
- Test coverage and test quality: Coverage gaps, weak assertions, tautological tests, missing scenarios. Are key code paths tested? Do tests actually validate correct behavior? Are unit tests well-structured with meaningful assertions?
  JUnit Jupiter silently DROPS a `@Test` method whose Kotlin return type is
  non-`Unit`: an expression-bodied `= runBlocking { ... }` test whose last
  statement returns a value (e.g. `assertFailsWith`, `assertIs`, `assertNotNull`)
  compiles to a non-void method and is never discovered — no error, no report
  entry. Keep the last statement a `Unit` assertion (bind the value and assert
  on it), and treat a class whose executed-test count is lower than its
  `@Test` count as a bug.
- Performance and security: Inefficiencies, resource leaks, injection risks, insecure defaults, exposed secrets, missing input validation.
- Code quality and style: follow the existing pattern (project conventions), no dark magic, no hacky solution/workaround, no complex logic without comments. Maintainability is the first priority.
  - Single Source of Truth for Comments: Only explain logic at its original/primary location. Other places referencing this logic MUST use pointers (e.g., `see [Component/File]`) and MUST NOT repeat the same explanation.
- File size budget: all main source code files must stay at or below 800
  lines — test files are excluded. A change that would push a file past
  the cap must refactor it first: split by cohesive responsibility into
  named modules. Grab-bag files (`Utils.kt`, `Helpers.kt`, `Common.kt`)
  are forbidden. They just relocate the problem.
- Test coverage: all core logic should have test covered for happy path, error path and common edge cases.

### Kotlin style

- Coroutine-native.
- Never log secrets (passwords, API keys, session cookies).
- Prefer fail-fast design

## Operational rule

- DO NOT touch git after making changes. User should review the change and manually stage the changes.
