package info.skyblond.fediverse.toot.trans.config.file

import info.skyblond.fediverse.toot.trans.config.ConfigProvider
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.Properties

/**
 * Read config from the given properties file.
 *
 * The file will be read only once during construction, as UTF-8.
 * */
class PropertiesConfigProvider(
    propertiesFile: File
) : ConfigProvider {
    private val properties = Properties()

    init {
        if (propertiesFile.exists()) {
            require(propertiesFile.isFile) { "Properties file `$propertiesFile` is not a file" }
            require(propertiesFile.canRead()) { "Properties file `$propertiesFile` is not readable" }

            // use a reader instead of an InputStream, so the file is read as UTF-8
            // rather than the ISO-8859-1 charset required by Properties.load(InputStream)
            propertiesFile.reader(Charsets.UTF_8).use { reader ->
                properties.load(reader)
            }
            logger.info { "Loaded properties from file `$propertiesFile`" }
        } else {
            logger.warn { "Properties file `$propertiesFile` not found" }
        }
    }

    override val mastoHost: String?
        get() = properties.getProperty("mastoHost")

    override val accessToken: String?
        get() = properties.getProperty("accessToken")

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
