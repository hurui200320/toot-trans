package info.skyblond.fediverse.toot.trans.di

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * The conventional proxy environment variables, in precedence order:
 * `HTTPS_PROXY`, `https_proxy`, `HTTP_PROXY`, `http_proxy`. Within
 * each pair the uppercase spelling is the more common one and wins.
 * */
private val PROXY_ENV_VARS = listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy")

/**
 * Resolve the proxy of the shared OkHttp client from the environment.
 *
 * The first variable of [PROXY_ENV_VARS] holding a non-blank value
 * wins; a blank value counts as unset, and with no variable set the
 * client connects directly (`null`). The value is a proxy URL such as
 * `http://127.0.0.1:3128`, used as a plain HTTP proxy for every
 * request: OkHttp tunnels even the HTTPS targets through it with
 * `CONNECT`, so the `HTTPS_`/`HTTP_` split picks which proxy to use,
 * not which proxy protocol to speak.
 *
 * Fail-fast, naming the variable: a malformed value, an `https://`
 * proxy URL (OkHttp connects to proxies in plaintext and cannot speak
 * TLS to one), and a URL carrying credentials (proxy authentication is
 * not supported) are all rejected, so a broken proxy setup cannot
 * silently degrade to a direct connection. The messages never echo the
 * value because it may embed credentials.
 *
 * [env] is injectable so tests can supply a fake environment;
 * production calls this with the real one, see [appModule].
 * */
internal fun proxyFromEnv(env: (String) -> String?): Proxy? {
    val (name, raw) = PROXY_ENV_VARS
        .firstNotNullOfOrNull { name ->
            env(name)?.trim()?.takeIf { it.isNotEmpty() }?.let { name to it }
        } ?: return null
    val url = raw.toHttpUrlOrNull()
        ?: throw IllegalArgumentException(
            "Environment variable `$name` must be a proxy URL such as `http://127.0.0.1:3128`"
        )
    require(!url.isHttps) {
        "Environment variable `$name` must use the `http://` scheme, " +
                "OkHttp connects to proxies in plaintext and cannot speak TLS to one"
    }
    require(url.username.isEmpty() && url.password.isEmpty()) {
        "Environment variable `$name` must not carry credentials, " +
                "proxy authentication is not supported"
    }
    return Proxy(Proxy.Type.HTTP, InetSocketAddress(url.host, url.port))
}
