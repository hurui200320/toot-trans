package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException


/**
 * Aggregate different [ConfigProvider]s.
 *
 * For each config, providers are asked in the given order and
 * the first non-null value wins.
 *
 * This is a terminal provider: unlike normal providers, it never returns null
 * as a fallback signal. If no provider can provide the config, it throws
 * [MissingConfigException]. Hence it MUST NOT be nested in another
 * [ConfigStore]'s provider list, otherwise the fallback of the outer store
 * is broken.
 * */
class ConfigStore(
    private val configProviders: List<ConfigProvider>,
) : ConfigProvider {
    override val mastoHost: String
        get() = getOrThrow("masto host") { it.mastoHost }

    override val accessToken: String
        get() = getOrThrow("access token") { it.accessToken }

    private fun <T> getOrThrow(
        name: String,
        mapper: (ConfigProvider) -> T?
    ): T = configProviders.firstNotNullOfOrNull(mapper)
        ?: throw MissingConfigException(name)
}
