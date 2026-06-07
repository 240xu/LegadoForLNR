package io.legado.plugin

import android.webkit.JavascriptInterface
import com.google.gson.Gson
import io.legado.engine.data.BookSource
import io.legado.engine.http.HttpClient
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject

class LegadoWebBridge(
    private val delegate: LoginJsBridge
) {
    @JavascriptInterface fun getKey(): String = delegate.getKey()
    @JavascriptInterface fun getTag(): String = delegate.getTag()
    @JavascriptInterface fun getLoginInfo(): String? = delegate.getLoginInfo()
    @JavascriptInterface fun putLoginInfo(info: String): Boolean = delegate.putLoginInfo(info)
    @JavascriptInterface fun putLoginHeader(header: String) = delegate.putLoginHeader(header)
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
    @JavascriptInterface fun getStrResponse(): String = delegate.getStrResponse()
    @JavascriptInterface fun open(type: String, url: String, title: String) = delegate.open(type, url, title)
    @JavascriptInterface fun open(url: String) = delegate.open("url", url, url)
    @JavascriptInterface fun getCookie(url: String): String = delegate.getCookie(url)
    @JavascriptInterface fun setCookie(url: String, cookie: String) = delegate.setCookie(url, cookie)
    @JavascriptInterface fun removeCookie(key: String) = delegate.removeCookie(key)
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
    @JavascriptInterface fun put(key: String, value: String): String {
        CacheManager.put(key, value)
        return value
    }
    @JavascriptInterface fun put(key: String, value: String, saveTimeSeconds: Int): String {
        CacheManager.put(key, value, saveTimeSeconds)
        return value
    }
    @JavascriptInterface fun delete(key: String) = CacheManager.delete(key)
    @JavascriptInterface fun remove(key: String) = CacheManager.delete(key)
    @JavascriptInterface fun contains(key: String): Boolean = CacheManager.contains(key)
}

fun parseLegadoHtmlSource(sourceJson: String): BookSource? =
    runCatching { Gson().fromJson(sourceJson, BookSource::class.java) }.getOrNull()

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
