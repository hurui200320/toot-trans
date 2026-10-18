package info.skyblond.fediverse.toot.trans.di

import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyFromEnvTest {

    private fun env(vararg entries: Pair<String, String>): (String) -> String? =
        mapOf(*entries)::get

    /**
     * The given resolved [Proxy] is an HTTP proxy at the given
     * host/port. The host is compared via `hostString` so a hostname
     * that the test JVM cannot resolve does not fail the test.
     * */
    private fun assertProxy(proxy: Proxy?, host: String, port: Int) {
        val resolved = assertNotNull(proxy)
        assertEquals(Proxy.Type.HTTP, resolved.type())
        val address = assertIs<InetSocketAddress>(resolved.address())
        assertEquals(host, address.hostString)
        assertEquals(port, address.port)
    }

    @Test
    fun `no proxy variables means a direct connection`() {
        assertNull(proxyFromEnv(env()))
    }

    @Test
    fun `variables resolve in precedence order`() {
        // the uppercase HTTPS spelling wins over everything else
        assertProxy(
            proxyFromEnv(
                env(
                    "HTTPS_PROXY" to "http://127.0.0.1:1111",
                    "https_proxy" to "http://127.0.0.1:2222",
                    "HTTP_PROXY" to "http://127.0.0.1:3333",
                    "http_proxy" to "http://127.0.0.1:4444",
                )
            ),
            "127.0.0.1", 1111,
        )
        // the lowercase HTTPS spelling beats both HTTP spellings
        assertProxy(
            proxyFromEnv(
                env(
                    "https_proxy" to "http://127.0.0.1:2222",
                    "HTTP_PROXY" to "http://127.0.0.1:3333",
                    "http_proxy" to "http://127.0.0.1:4444",
                )
            ),
            "127.0.0.1", 2222,
        )
        // the uppercase HTTP spelling beats the lowercase one
        assertProxy(
            proxyFromEnv(
                env(
                    "HTTP_PROXY" to "http://127.0.0.1:3333",
                    "http_proxy" to "http://127.0.0.1:4444",
                )
            ),
            "127.0.0.1", 3333,
        )
        // the lowercase HTTP spelling is the last resort
        assertProxy(
            proxyFromEnv(env("http_proxy" to "http://127.0.0.1:4444")),
            "127.0.0.1", 4444,
        )
    }

    @Test
    fun `blank values count as unset`() {
        assertNull(proxyFromEnv(env("HTTPS_PROXY" to "")))
        assertNull(proxyFromEnv(env("HTTPS_PROXY" to "   ")))
        assertProxy(
            proxyFromEnv(env("HTTPS_PROXY" to " ", "http_proxy" to "http://127.0.0.1:4444")),
            "127.0.0.1", 4444,
        )
    }

    @Test
    fun `the host and port come from the proxy URL`() {
        assertProxy(
            proxyFromEnv(env("HTTPS_PROXY" to "http://127.0.0.1:3128")),
            "127.0.0.1", 3128,
        )
        // the scheme supplies the default port when the URL omits it
        assertProxy(
            proxyFromEnv(env("HTTPS_PROXY" to "http://proxy.example")),
            "proxy.example", 80,
        )
    }

    @Test
    fun `malformed values fail fast naming the variable without echoing the value`() {
        for (raw in listOf("not a url", "socks5://127.0.0.1:1080")) {
            val error = assertFailsWith<IllegalArgumentException> {
                proxyFromEnv(env("HTTPS_PROXY" to raw))
            }
            assertTrue(error.message!!.contains("HTTPS_PROXY"), "error must name the variable: ${error.message}")
            // the value may embed credentials, so it must not be echoed
            assertFalse(error.message!!.contains(raw), "error must not echo the value: ${error.message}")
        }
    }

    @Test
    fun `https proxy URLs are rejected`() {
        // OkHttp connects to proxies in plaintext, so an https:// proxy
        // URL would only be misleading; the error points at http://
        val error = assertFailsWith<IllegalArgumentException> {
            proxyFromEnv(env("HTTPS_PROXY" to "https://127.0.0.1:3128"))
        }
        assertTrue(error.message!!.contains("HTTPS_PROXY"), "error must name the variable: ${error.message}")
        assertTrue(error.message!!.contains("http://"), "error must point at http://: ${error.message}")
    }

    @Test
    fun `credential-carrying proxy URLs are rejected without echoing them`() {
        val error = assertFailsWith<IllegalArgumentException> {
            proxyFromEnv(env("HTTPS_PROXY" to "http://user:secret@127.0.0.1:3128"))
        }
        assertTrue(error.message!!.contains("HTTPS_PROXY"), "error must name the variable: ${error.message}")
        assertFalse(error.message!!.contains("secret"), "error must not leak the credentials: ${error.message}")
    }
}
