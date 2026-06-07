package io.legado.engine.http

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import io.legado.engine.shim.AppConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

object HttpClient {
    val client: OkHttpClient by lazy {
        val specs = listOf(
            ConnectionSpec.MODERN_TLS,
            ConnectionSpec.COMPATIBLE_TLS,
            ConnectionSpec.CLEARTEXT
        )
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(CookieJarImpl())
            .sslSocketFactory(SSLHelper.unsafeSSLSocketFactory, SSLHelper.unsafeTrustManager)
            .hostnameVerifier(SSLHelper.unsafeHostnameVerifier)
            .connectionSpecs(specs)
            .addInterceptor(DecompressInterceptor)
            .addInterceptor { chain ->
                val request = chain.request()
                val builder = request.newBuilder()
                if (request.header("User-Agent") == null) {
                    builder.addHeader("User-Agent", AppConfig.userAgent)
                }
                builder.addHeader("Keep-Alive", "300")
                builder.addHeader("Connection", "Keep-Alive")
                builder.addHeader("Cache-Control", "no-cache")
                chain.proceed(builder.build())
            }
            .build()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        return request("GET", url, null, headers)
    }

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        charset: String? = null,
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): HttpResponse {
        return request("GET", url, null, headers, charset, proxy, dnsIp, timeoutMillis)
    }

    fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        return request("POST", url, body, headers)
    }

    fun post(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        charset: String? = null,
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): HttpResponse {
        return request("POST", url, body, headers, charset, proxy, dnsIp, timeoutMillis)
    }

    fun postForm(
        url: String,
        formBody: String,
        headers: Map<String, String> = emptyMap(),
        charset: String? = null,
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): HttpResponse {
        val merged = headers.toMutableMap()
        merged["Content-Type"] = "application/x-www-form-urlencoded"
        return request("POST", url, formBody, merged, charset, proxy, dnsIp, timeoutMillis)
    }

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap(),
        charset: String? = null,
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): HttpResponse {
        val merged = headers.toMutableMap()
        merged["Content-Type"] = "application/json; charset=utf-8"
        return request("POST", url, jsonBody, merged, charset, proxy, dnsIp, timeoutMillis)
    }

    fun head(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): HttpResponse {
        return request("HEAD", url, null, headers, null, proxy, dnsIp, timeoutMillis)
    }

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String> = emptyMap(),
        charsetName: String? = null,
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): HttpResponse {
        val merged = buildHeaders(url, headers)
        val mediaType = resolveMediaType(merged)
        val builder = Request.Builder().url(url)
        when (method.uppercase()) {
            "POST" -> builder.post((body ?: "").toRequestBody(mediaType))
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        merged.forEach { (k, v) -> builder.addHeader(k, v) }
        val resp = clientFor(proxy, dnsIp, timeoutMillis).newCall(builder.build()).execute()
        val respBody = when {
            method.equals("HEAD", true) -> ""
            charsetName.isNullOrBlank() -> resp.body?.string() ?: ""
            else -> resp.body?.bytes()?.let { String(it, safeCharset(charsetName)) } ?: ""
        }
        saveCookies(resp)
        return HttpResponse(resp.request.url.toString(), respBody, resp.code, resp.headers.toCaseInsensitiveMultimap())
    }

    fun getByteArray(
        url: String,
        headers: Map<String, String> = emptyMap(),
        proxy: String? = null,
        dnsIp: String? = null,
        timeoutMillis: Long? = null
    ): ByteArray {
        val merged = buildHeaders(url, headers)
        val builder = Request.Builder().url(url).get()
        merged.forEach { (k, v) -> builder.addHeader(k, v) }
        val resp = clientFor(proxy, dnsIp, timeoutMillis).newCall(builder.build()).execute()
        return resp.body?.bytes() ?: ByteArray(0)
    }

    private fun buildHeaders(url: String, custom: Map<String, String>): MutableMap<String, String> {
        val merged = mutableMapOf<String, String>()
        merged.putAll(CookieStore.getCookieHeader(url))
        merged.putAll(custom)
        if (!merged.containsKey("User-Agent") && !merged.containsKey("user-agent")) {
            merged["User-Agent"] = AppConfig.userAgent
        }
        return merged
    }

    private fun resolveMediaType(headers: Map<String, String>): MediaType? {
        val contentType = headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
        return when {
            !contentType.isNullOrBlank() -> contentType.toMediaTypeOrNull()
            else -> "text/plain; charset=utf-8".toMediaTypeOrNull()
        }
    }

    internal fun clientFor(proxy: String?, dnsIp: String?, timeoutMillis: Long?): OkHttpClient {
        if (proxy.isNullOrBlank() && dnsIp.isNullOrBlank() && timeoutMillis == null) return client
        val builder = client.newBuilder()
        if (timeoutMillis != null && timeoutMillis > 0) {
            builder.callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            builder.connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            builder.readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        }
        parseProxy(proxy)?.let { builder.proxy(it) }
        if (!dnsIp.isNullOrBlank()) {
            builder.dns { listOf(InetAddress.getByName(dnsIp)) }
        }
        return builder.build()
    }

    private fun parseProxy(raw: String?): Proxy? {
        if (raw.isNullOrBlank()) return null
        val clean = raw.substringBefore("@")
        val uri = try { java.net.URI(clean) } catch (_: Exception) { return null }
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else return null
        val type = when (uri.scheme?.lowercase()) {
            "socks", "socks4", "socks5" -> Proxy.Type.SOCKS
            "http", "https" -> Proxy.Type.HTTP
            else -> return null
        }
        return Proxy(type, InetSocketAddress(host, port))
    }

    private fun safeCharset(name: String): Charset {
        return try { Charset.forName(name) } catch (_: Exception) { Charsets.UTF_8 }
    }

    private fun saveCookies(resp: Response) {
        val domain = resp.request.url.host
        resp.headers("Set-Cookie").forEach { CookieStore.setCookie(domain, it) }
    }

    /**
     * 注入外部 header map（例如 loginHeader / loginUi 回灌的 header）。
     * 从 map 中提取 Cookie 字段合并到 CookieStore，
     * 其余 header 作为请求头透传到请求中（由调用方决定是否使用）。
     */
    fun injectLoginHeader(domain: String, headerMap: Map<String, String>?) {
        headerMap ?: return
        headerMap["Cookie"]?.takeIf { it.isNotBlank() }?.let {
            CookieStore.setCookie(domain, it)
        }
    }
}

private fun Headers.toCaseInsensitiveMultimap(): Map<String, List<String>> {
    val result = linkedMapOf<String, MutableList<String>>()
    for (name in names()) {
        result.getOrPut(name.lowercase()) { mutableListOf() }.addAll(values(name))
        result.getOrPut(name) { mutableListOf() }.addAll(values(name))
    }
    return result
}

private class CookieJarImpl : okhttp3.CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val domain = url.host
        val str = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        if (str.isNotBlank()) CookieStore.setCookie(domain, str)
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val domain = url.host
        val str = CookieStore.getCookie(domain)
        if (str.isBlank()) return emptyList()
        return str.split(";").mapNotNull {
            val idx = it.indexOf("=")
            if (idx > 0) Cookie.Builder().domain(domain).path("/")
                .name(it.substring(0, idx).trim()).value(it.substring(idx + 1).trim()).build()
            else null
        }
    }
}

data class HttpResponse(
    val url: String = "",
    val body: String = "",
    val code: Int = 200,
    val headers: Map<String, List<String>> = emptyMap()
) {
    fun header(name: String): String = headers[name]?.firstOrNull() ?: headers[name.lowercase()]?.firstOrNull() ?: ""
    fun isSuccessful(): Boolean = code in 200..299
}
