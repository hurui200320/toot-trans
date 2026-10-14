package info.skyblond.fediverse.toot.trans.config

class DefaultConfigProvider : ConfigProvider {
    // required config, no default
    override val mastoHost: String? = null

    // required config, no default
    override val accessToken: String? = null
}
