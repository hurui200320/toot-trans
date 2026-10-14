package info.skyblond.fediverse.toot.trans.config

import info.skyblond.fediverse.toot.trans.config.exception.MissingConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigStoreTest {
    private fun provider(
        mastoHost: String? = null,
        accessToken: String? = null,
    ): ConfigProvider = object : ConfigProvider {
        override val mastoHost: String? = mastoHost
        override val accessToken: String? = accessToken
    }

    @Test
    fun `first provider wins`() {
        val store = ConfigStore(
            listOf(
                provider(mastoHost = "https://first.example/", accessToken = "token1"),
                provider(mastoHost = "https://second.example/", accessToken = "token2"),
            )
        )

        assertEquals("https://first.example/", store.mastoHost)
        assertEquals("token1", store.accessToken)
    }

    @Test
    fun `null values are skipped and later providers are used`() {
        val store = ConfigStore(
            listOf(
                // nothing configured
                provider(),
                // only host, no token
                provider(mastoHost = "https://second.example/"),
                // both configured, should only be used for the token
                provider(mastoHost = "https://third.example/", accessToken = "token"),
            )
        )

        assertEquals("https://second.example/", store.mastoHost)
        assertEquals("token", store.accessToken)
    }

    @Test
    fun `throws when no provider supplies the config`() {
        val store = ConfigStore(
            listOf(
                provider(),
                provider(mastoHost = "https://partial.example/"),
            )
        )

        // host is available, token is not
        assertEquals("https://partial.example/", store.mastoHost)
        val exception = assertFailsWith<MissingConfigException> { store.accessToken }
        assertTrue(exception.message!!.contains("access token"))
    }

    @Test
    fun `throws on empty provider list`() {
        val store = ConfigStore(emptyList())

        assertFailsWith<MissingConfigException> { store.mastoHost }
    }
}
