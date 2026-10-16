package info.skyblond.fediverse.toot.trans.config.file

import info.skyblond.fediverse.toot.trans.config.ConfigProvider
import info.skyblond.fediverse.toot.trans.config.parseDurationConfig
import info.skyblond.fediverse.toot.trans.config.parseIntConfig
import info.skyblond.fediverse.toot.trans.config.parseStringConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.Properties
import kotlin.time.Duration

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

    override val poolBatchWindow: Duration?
        get() = duration("poolBatchWindow")

    override val poolCacheTtl: Duration?
        get() = duration("poolCacheTtl")

    override val poolMaxCacheEntries: Int?
        get() = int("poolMaxCacheEntries")

    override val backoffResetMargin: Duration?
        get() = duration("backoffResetMargin")

    override val backoffFallbackDelay: Duration?
        get() = duration("backoffFallbackDelay")

    override val backoffMaxWaitDuration: Duration?
        get() = duration("backoffMaxWaitDuration")

    override val initialScanSize: Int?
        get() = int("initialScanSize")

    override val scanTransientMaxRetry: Int?
        get() = int("scanTransientMaxRetry")

    override val scanTransientRetryDelay: Duration?
        get() = duration("scanTransientRetryDelay")

    override val translationCooldown: Duration?
        get() = duration("translationCooldown")

    override val targetLanguage: String?
        get() = string("targetLanguage")

    override val targetLangCode: String?
        get() = string("targetLangCode")

    private fun duration(key: String): Duration? =
        parseDurationConfig(key, properties.getProperty(key))

    private fun int(key: String): Int? =
        parseIntConfig(key, properties.getProperty(key))

    private fun string(key: String): String? =
        parseStringConfig(properties.getProperty(key))

    companion object {
        private val logger = KotlinLogging.logger { }
    }
}
