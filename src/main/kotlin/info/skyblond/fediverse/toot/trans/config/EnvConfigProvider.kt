package info.skyblond.fediverse.toot.trans.config

/**
 * Read config from the environment variables.
 * */
class EnvConfigProvider : ConfigProvider {
    override val mastoHost: String?
        get() = System.getenv("MASTO_HOST")

    override val accessToken: String?
        get() = System.getenv("ACCESS_TOKEN")
}
