package info.skyblond.fediverse.toot.trans.config.exception

/**
 * Thrown when no [info.skyblond.fediverse.toot.trans.config.ConfigProvider]
 * can provide a required config.
 *
 * See [info.skyblond.fediverse.toot.trans.config.ConfigStore].
 * */
class MissingConfigException(
    configName: String,
) : IllegalStateException("No value provided for config: $configName")
