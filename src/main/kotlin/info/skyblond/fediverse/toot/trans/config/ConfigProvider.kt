package info.skyblond.fediverse.toot.trans.config

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
}
