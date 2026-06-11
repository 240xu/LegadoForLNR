package io.legado.plugin

import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import com.google.gson.Gson
import io.legado.engine.data.BookSource
import io.legado.engine.http.CookieStore
import io.legado.engine.http.HttpClient
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject

class LegadoJavaWebBridge(
    private val delegate: LoginJsBridge
) {
    @JavascriptInterface fun toast(msg: String?) = delegate.toast(msg)
    @JavascriptInterface fun longToast(msg: String?) = delegate.longToast(msg)
    @JavascriptInterface fun log(msg: String?) = delegate.log(msg)
    @JavascriptInterface fun ajax(url: String): String = delegate.ajax(url)
    @JavascriptInterface fun ajax(url: String, callTimeout: Int): String = delegate.ajax(url)
    @JavascriptInterface fun connect(url: String): String = delegate.connect(url).toString()
    @JavascriptInterface fun connect(url: String, headers: String?): String = delegate.connect(url, headers).toString()
    @JavascriptInterface fun connect(url: String, headers: String?, callTimeout: Int): String =
        delegate.connect(url, headers, callTimeout.toLong()).toString()
    @JavascriptInterface fun get(url: String, headers: String): String = get(url, headers, 9000)
    @JavascriptInterface fun get(url: String, headers: String, timeout: Int): String =
        runCatching { HttpClient.get(url, parseBridgeHeaders(headers), timeoutMillis = timeout.toLong()).body }.getOrDefault("")
    @JavascriptInterface fun getUrl(url: String): String = delegate.ajax(url)
    @JavascriptInterface fun getUrl(url: String, headers: String?): String = delegate.ajax(url, headers)
    @JavascriptInterface fun post(url: String, body: String?): String = delegate.post(url, body)
    @JavascriptInterface fun post(url: String, body: String?, headers: String): String = post(url, body, headers, 9000)
    @JavascriptInterface fun post(url: String, body: String?, headers: String, timeout: Int): String =
        runCatching {
            HttpClient.post(url, body.orEmpty(), parseBridgeHeaders(headers), timeoutMillis = timeout.toLong()).body
        }.getOrDefault("")
    @JavascriptInterface fun head(url: String, headers: String): String = head(url, headers, 9000)
    @JavascriptInterface fun head(url: String, headers: String, timeout: Int): String =
        runCatching { Gson().toJson(HttpClient.head(url, parseBridgeHeaders(headers), timeoutMillis = timeout.toLong()).headers) }
            .getOrDefault("{}")
    @JavascriptInterface fun getStrResponse(): String = delegate.getStrResponseBody()
    @JavascriptInterface fun initUrl() = delegate.initUrl()
    @JavascriptInterface fun open(type: String, url: String, title: String) = delegate.open(type, url, title)
    @JavascriptInterface fun open(url: String) = delegate.open("url", url, url)
    @JavascriptInterface fun showBrowser(url: String) = delegate.showBrowser(url)
    @JavascriptInterface fun showBrowser(url: String, html: String?) = delegate.showBrowser(url, html)
    @JavascriptInterface fun showBrowser(url: String, html: String?, preloadJs: String?) =
        delegate.showBrowser(url, html, preloadJs)
    @JavascriptInterface fun showBrowser(url: String, html: String?, preloadJs: String?, config: String?) =
        delegate.showBrowser(url, html, preloadJs, config)
    @JavascriptInterface fun startBrowser(url: String, title: String) = delegate.startBrowser(url, title)
    @JavascriptInterface fun startBrowserDp(url: String, title: String) = delegate.startBrowserDp(url, title)
    @JavascriptInterface fun openUrl(url: String) = delegate.openUrl(url)
    @JavascriptInterface fun openUrl(url: String, mimeType: String?) = delegate.openUrl(url, mimeType)
    @JavascriptInterface fun importScript(path: String): String = delegate.importScript(path)
    @JavascriptInterface fun copyText(text: String) = delegate.copyText(text)
    @JavascriptInterface fun getCookie(url: String): String = delegate.getCookie(url)
    @JavascriptInterface fun setCookie(url: String, cookie: String) = delegate.setCookie(url, cookie)
    @JavascriptInterface fun removeCookie(key: String) = delegate.removeCookie(key)
    @JavascriptInterface fun encodeURI(str: String): String = delegate.encodeURI(str)
    @JavascriptInterface fun decodeURI(str: String): String = delegate.decodeURI(str)
    @JavascriptInterface fun encodeURIComponent(str: String): String = delegate.encodeURIComponent(str)
    @JavascriptInterface fun decodeURIComponent(str: String): String = delegate.decodeURIComponent(str)
    @JavascriptInterface fun urlEncode(str: String): String = delegate.urlEncode(str)
    @JavascriptInterface fun urlDecode(str: String): String = delegate.urlDecode(str)
    @JavascriptInterface fun base64Encode(str: String): String = delegate.base64Encode(str)
    @JavascriptInterface fun base64Decode(str: String): String = delegate.base64Decode(str)
    @JavascriptInterface fun md5(str: String): String = delegate.md5(str)
    @JavascriptInterface fun sha1(str: String): String = delegate.sha1(str)
    @JavascriptInterface fun sha256(str: String): String = delegate.sha256(str)
    @JavascriptInterface fun randomUUID(): String = delegate.randomUUID()
    @JavascriptInterface fun androidId(): String = delegate.androidId()
    @JavascriptInterface fun currentTimeMillis(): Long = delegate.currentTimeMillis()
    @JavascriptInterface fun upLoginData(data: String?) = delegate.upLoginData(data)
    @JavascriptInterface fun reLoginView() = delegate.reLoginView()
    @JavascriptInterface fun reLoginView(deltaUp: Boolean) = delegate.reLoginView(deltaUp)
}

class LegadoWebBridge(
    private val delegate: LoginJsBridge
) {
    @JavascriptInterface fun getKey(): String = delegate.getKey()
    @JavascriptInterface fun getTag(): String = delegate.getTag()
    @JavascriptInterface fun login() = delegate.login()
    @JavascriptInterface fun getLoginInfo(): String? = delegate.getLoginInfo()
    @JavascriptInterface fun getLoginHeader(): String? = delegate.getLoginHeader()
    @JavascriptInterface fun putLoginInfo(info: String): Boolean = delegate.putLoginInfo(info)
    @JavascriptInterface fun putLoginHeader(header: String) = delegate.putLoginHeader(header)
    @JavascriptInterface fun removeLoginInfo() = delegate.removeLoginInfo()
    @JavascriptInterface fun removeLoginHeader() = delegate.removeLoginHeader()
    @JavascriptInterface fun getVariable(): String = delegate.getVariable()
    @JavascriptInterface fun getVariable(key: String): String = delegate.getVariable(key)
    @JavascriptInterface fun setVariable(value: String?) = delegate.setVariable(value)
    @JavascriptInterface fun putVariable(value: String?): String = delegate.putVariable(value)
    @JavascriptInterface fun putVariable(key: String, value: String): String = delegate.putVariable(key, value)
    @JavascriptInterface fun get(key: String): String = delegate.get(key)
    @JavascriptInterface fun put(key: String, value: String): String = delegate.put(key, value)
    @JavascriptInterface fun ajax(url: String): String = delegate.ajax(url)
    @JavascriptInterface fun ajax(url: String, headers: String?): String = delegate.ajax(url, headers)
    @JavascriptInterface fun getUrl(url: String): String = delegate.ajax(url)
    @JavascriptInterface fun getUrl(url: String, headers: String?): String = delegate.ajax(url, headers)
    @JavascriptInterface fun post(url: String, body: String?): String = delegate.post(url, body)
    @JavascriptInterface fun post(url: String, body: String?, headers: String?): String = delegate.post(url, body, headers)
    @JavascriptInterface fun connect(url: String): String = delegate.connect(url).body().orEmpty()
    @JavascriptInterface fun connect(url: String, headers: String?): String = delegate.connect(url, headers).body().orEmpty()
    @JavascriptInterface fun getStrResponse(): String = delegate.getStrResponseBody()
    @JavascriptInterface fun initUrl() = delegate.initUrl()
    @JavascriptInterface fun open(type: String, url: String, title: String) = delegate.open(type, url, title)
    @JavascriptInterface fun open(url: String) = delegate.open("url", url, url)
    @JavascriptInterface fun showBrowser(url: String) = delegate.showBrowser(url)
    @JavascriptInterface fun showBrowser(url: String, html: String?) = delegate.showBrowser(url, html)
    @JavascriptInterface fun startBrowser(url: String, title: String) = delegate.startBrowser(url, title)
    @JavascriptInterface fun openUrl(url: String) = delegate.openUrl(url)
    @JavascriptInterface fun importScript(path: String): String = delegate.importScript(path)
    @JavascriptInterface fun copyText(text: String) = delegate.copyText(text)
    @JavascriptInterface fun getCookie(url: String): String = delegate.getCookie(url)
    @JavascriptInterface fun setCookie(url: String, cookie: String) = delegate.setCookie(url, cookie)
    @JavascriptInterface fun removeCookie(key: String) = delegate.removeCookie(key)
    @JavascriptInterface fun upLoginData(data: String?) = delegate.upLoginData(data)
    @JavascriptInterface fun reLoginView() = delegate.reLoginView()
    @JavascriptInterface fun reLoginView(deltaUp: Boolean) = delegate.reLoginView(deltaUp)
    @JavascriptInterface fun toast(msg: String?) = delegate.toast(msg)
    @JavascriptInterface fun longToast(msg: String?) = delegate.longToast(msg)
    @JavascriptInterface fun log(msg: String?) = delegate.log(msg)
    @JavascriptInterface fun refreshExplore() = delegate.refreshExplore()
    @JavascriptInterface fun refreshBookInfo() = delegate.refreshBookInfo()
    @JavascriptInterface fun refreshBookToc() = delegate.refreshBookToc()
    @JavascriptInterface fun refreshContent() = delegate.refreshContent()
    @JavascriptInterface fun encodeURI(str: String): String = delegate.encodeURI(str)
    @JavascriptInterface fun decodeURI(str: String): String = delegate.decodeURI(str)
    @JavascriptInterface fun encodeURIComponent(str: String): String = delegate.encodeURIComponent(str)
    @JavascriptInterface fun decodeURIComponent(str: String): String = delegate.decodeURIComponent(str)
    @JavascriptInterface fun urlEncode(str: String): String = delegate.urlEncode(str)
    @JavascriptInterface fun urlDecode(str: String): String = delegate.urlDecode(str)
    @JavascriptInterface fun base64Encode(str: String): String = delegate.base64Encode(str)
    @JavascriptInterface fun base64Decode(str: String): String = delegate.base64Decode(str)
    @JavascriptInterface fun md5(str: String): String = delegate.md5(str)
    @JavascriptInterface fun sha1(str: String): String = delegate.sha1(str)
    @JavascriptInterface fun sha256(str: String): String = delegate.sha256(str)
    @JavascriptInterface fun randomUUID(): String = delegate.randomUUID()
    @JavascriptInterface fun androidId(): String = delegate.androidId()
    @JavascriptInterface fun currentTimeMillis(): Long = delegate.currentTimeMillis()
}

class LegadoCacheWebBridge {
    @JavascriptInterface fun get(key: String): String = CacheManager.get(key).orEmpty()
    @JavascriptInterface fun get(key: String, onlyDisk: Boolean): String =
        if (onlyDisk) CacheManager.get(key).orEmpty() else CacheManager.getFromMemory(key)?.toString() ?: CacheManager.get(key).orEmpty()
    @JavascriptInterface fun put(key: String, value: String): String {
        CacheManager.put(key, value)
        return value
    }
    @JavascriptInterface fun put(key: String, value: String, saveTimeSeconds: Int): String {
        CacheManager.put(key, value, saveTimeSeconds)
        return value
    }
    @JavascriptInterface fun putMemory(key: String, value: String) = CacheManager.putMemory(key, value)
    @JavascriptInterface fun getFromMemory(key: String): String = CacheManager.getFromMemory(key)?.toString().orEmpty()
    @JavascriptInterface fun deleteMemory(key: String) = CacheManager.deleteMemory(key)
    @JavascriptInterface fun putFile(key: String, value: String) = CacheManager.put(key, value)
    @JavascriptInterface fun putFile(key: String, value: String, saveTimeSeconds: Int) = CacheManager.put(key, value, saveTimeSeconds)
    @JavascriptInterface fun getFile(key: String): String = CacheManager.get(key).orEmpty()
    @JavascriptInterface fun delete(key: String) = CacheManager.delete(key)
    @JavascriptInterface fun remove(key: String) = CacheManager.delete(key)
    @JavascriptInterface fun contains(key: String): Boolean = CacheManager.contains(key)
}

private fun parseBridgeHeaders(headers: String?): Map<String, String> {
    if (headers.isNullOrBlank()) return emptyMap()
    return UrlOptionParser.parseHeaders(headers)
}

fun parseLegadoHtmlSource(sourceJson: String): BookSource? =
    LegadoSourceJson.parseSource(sourceJson)

fun resolveLegadoWebJsLib(source: BookSource): String = resolveLegadoWebJsLib(source.jsLib)

fun resolveLegadoWebJsLib(jsLib: String?): String {
    val raw = jsLib?.takeIf { it.isNotBlank() } ?: return ""
    val trimmed = raw.trim()
    if (!trimmed.startsWith("{")) return raw
    val entries = runCatching { GSON.fromJsonObject<Map<String, String>>(trimmed) }
        .getOrNull()
        ?: return raw
    return entries.values.joinToString("\n") { value ->
        val item = value.trim()
        if (item.startsWith("http://", true) || item.startsWith("https://", true)) {
            runCatching { HttpClient.get(UrlOptionParser.strip(item)).body }.getOrDefault("")
        } else {
            value
        }
    }
}

fun injectLegadoWebBootstrap(html: String, jsLib: String?, sourceUrl: String, baseUrl: String): String {
    val script = legadoWebBootstrapScript(jsLib, sourceUrl, baseUrl)
    if (script.isBlank()) return html
    val block = "<script>\n$script\n</script>"
    val headRegex = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    val match = headRegex.find(html)
    return if (match != null) {
        html.substring(0, match.range.last + 1) + block + html.substring(match.range.last + 1)
    } else {
        block + html
    }
}

fun legadoWebBootstrapScript(jsLib: String?, sourceUrl: String, baseUrl: String): String {
    val gson = Gson()
    val key = gson.toJson(sourceUrl)
    val base = gson.toJson(baseUrl)
    val lib = jsLib.orEmpty()
    return """
        (function(){
          try { window.baseUrl = $base; } catch(e) {}
          try { window.sourceUrl = $key; } catch(e) {}
          try { if (window.source && source.getKey) { source.key = source.getKey(); source.tag = source.getTag(); } } catch(e) {}
          try { if (window.java && !java.get && java.getUrl) { java.get = java.getUrl; } } catch(e) {}
        })();
        $lib
    """.trimIndent()
}

fun injectStoredCookiesToWebView(targetUrl: String) {
    if (!targetUrl.startsWith("http://", true) && !targetUrl.startsWith("https://", true)) return
    val stored = CookieStore.getCookieHeader(targetUrl)["Cookie"].orEmpty()
    if (stored.isBlank()) return
    CookieManager.getInstance().setCookie(targetUrl, stored)
    CookieManager.getInstance().flush()
}
