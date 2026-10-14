# AGENTS.md

Guidance for AI agents (and humans) working in this project.

> This file acts as the TOC and a map of the repo.
> Its purpose is to help humans and LLMs quickly locate related components without exploring the whole codebase.
> It MUST NOT repeat comments from the code or explain things that are obvious by just taking a look at the code.

## Contents

- [Project](#project)
- [Repo map](#repo-map)
- [Architecture](#architecture)
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

## Repo map

Base package for toot translation: `info.skyblond.fediverse.toot.trans` (avoid using Mastodon in code due to copyright issues).

## Architecture

This project is built with no-state in mind.

To avoid slow updates of Mastodon API libraries, this project us OkHttp
to access API directly, parsing stuff we only need.

For LLM translation, this project accepts standard OpenAI compatible
chat completion API settings.

## Verification commands

```bash
./gradlew test
```

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
  - Wire Protocol Documentation: see [Backend style](#backend-style).
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
