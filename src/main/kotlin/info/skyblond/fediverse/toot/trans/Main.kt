package info.skyblond.fediverse.toot.trans

import info.skyblond.fediverse.toot.trans.config.ConfigStore
import info.skyblond.fediverse.toot.trans.config.DefaultConfigProvider
import info.skyblond.fediverse.toot.trans.config.EnvConfigProvider
import info.skyblond.fediverse.toot.trans.config.file.PropertiesConfigProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

private val logger = KotlinLogging.logger("Application")

fun main() {
    val config = ConfigStore(
        listOf(
            EnvConfigProvider(),
            PropertiesConfigProvider(
                File(
                    System.getenv("CONFIG_PROPERTIES_PATH")
                        ?: "./config.properties"
                )
            ),
            DefaultConfigProvider()
        )
    )

    // resolve the config outside of the logger lambda:
    // kotlin-logging swallows exceptions thrown inside the lambda,
    // which would turn a missing config into a silent, clean exit
    val mastoHost = config.mastoHost
    logger.info { "Instance host: $mastoHost" }
}
