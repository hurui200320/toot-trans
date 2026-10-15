# toot-trans

Automatically translate toots.

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

Example `config.properties`:

```properties
mastoHost=https://mastodon.social/
accessToken=your-access-token
```

## Shared status lookups

Use `StatusesPool` for status-by-ID reads instead of calling the HTTP client
directly. Its KDoc in
`src/main/kotlin/info/skyblond/fediverse/toot/trans/masto/statuses/StatusesPool.kt`
documents cache, batching, retry, and lifecycle settings.

```kotlin
val pool = StatusesPool(statusesClient, applicationScope)
try {
    pool.seed(statusesFromAccountPolling)
    val parent = pool.getStatus(parentId)
    val refreshed = pool.getStatus(parentId, bypassCache = true)
    // After successfully deleting a status:
    pool.invalidate(deletedId)
} finally {
    pool.shutdown()
}
```

Lookups return `null` for statuses unavailable to the current token.
Cache bypass still obeys the pool's batching and rate-limit handling.
Account polling, status-source reads, posting, and deletion remain outside
the pool and must handle their own rate limits.
