package io.legado.engine.rule

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.engine.constant.AppConst
import io.legado.engine.constant.AppPattern
import io.legado.engine.data.BaseSource
import io.legado.engine.data.Book
import io.legado.engine.data.BookChapter
import io.legado.engine.data.RuleDataInterface
import io.legado.engine.http.CookieStore
import io.legado.engine.http.HttpClient
import io.legado.engine.http.HttpResponse
import io.legado.engine.http.StrResponse
import io.legado.engine.js.JsExtensions
import io.legado.engine.js.SharedJsScope
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.Debug
import io.legado.engine.shim.GSON
import io.legado.engine.webview.BackstageWebView
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.regex.Pattern
import kotlin.math.max

class AnalyzeUrl(
    private val mUrl: String,
    private val key: String? = null,
    private val page: Int? = null,
    private var baseUrl: String = "",
    private val source: BaseSource? = null,
    private val ruleData: RuleDataInterface? = null,
    private val chapter: BookChapter? = null,
    private val callTimeout: Long? = null,
    headerMapF: Map<String, String>? = null,
    hasLoginHeader: Boolean = true,
    private val infoMap: MutableMap<String, String>? = null
) : JsExtensions {

    var ruleUrl = ""; private set
    var url: String = ""; private set
    var urlNoQuery: String = ""; private set
    var type: String? = null; private set
    val headerMap = LinkedHashMap<String, String>()
    private var body: String? = null
    private var encodedForm: String? = null
    private var encodedQuery: String? = null
    private var charset: String? = null
    private var method = "GET"
    private var proxy: String? = null
    private var retry: Int = 0
    private var useWebView: Boolean = false
    private var webJs: String? = null
    private var bodyJs: String? = null
    private var dnsIp: String? = null
    private var webViewDelayTime: Long = 0
    private var dataBody: String? = null
    // lyc 源默认不设此字段时当作 true，确保 cookie 能正常收发
    private val enabledCookieJar = source?.enabledCookieJar != false
    private val domain: String

    init {
        (headerMapF ?: source?.getHeaderMap(hasLoginHeader))?.let {
            headerMap.putAll(it)
            if (it.containsKey("proxy")) { proxy = it["proxy"]; headerMap.remove("proxy") }
        }
        initUrl()
        domain = try { URL(source?.getKey() ?: url).host } catch (_: Exception) { "" }
    }

    fun initUrl() {
        ruleUrl = mUrl
        analyzeJs()
        replaceKeyPageJs()
        analyzeUrl()
    }

    private fun analyzeJs() {
        var start = 0
        val jsMatcher = AppPattern.JS_PATTERN.matcher(ruleUrl)
        var result = ruleUrl
        while (jsMatcher.find()) {
            if (jsMatcher.start() > start) {
                ruleUrl.substring(start, jsMatcher.start()).trim().let {
                    if (it.isNotEmpty()) result = it.replace("@result", result)
                }
            }
            result = evalJS(jsMatcher.group(2) ?: jsMatcher.group(1) ?: "", result)?.toString() ?: result
            start = jsMatcher.end()
        }
        if (ruleUrl.length > start) {
            ruleUrl.substring(start).trim().let {
                if (it.isNotEmpty()) result = it.replace("@result", result)
            }
        }
        ruleUrl = result
    }

    private fun replaceKeyPageJs() {
        if (ruleUrl.contains("{{") && ruleUrl.contains("}}")) {
            val analyze = RuleAnalyzer(ruleUrl)
            val url = analyze.innerRule("{{", "}}") {
                val jsEval = if (it.startsWith("$.") || it.startsWith("$[")) getString(it) else evalJS(it) ?: ""
                when (jsEval) {
                    is String -> jsEval
                    is Double -> if (jsEval % 1.0 == 0.0) "%.0f".format(jsEval) else jsEval.toString()
                    else -> jsEval.toString()
                }
            }
            if (url.isNotEmpty()) ruleUrl = url
        }
        key?.let { k ->
            val encodedKey = try { URLEncoder.encode(k, "UTF-8") } catch (_: Exception) { k }
            ruleUrl = ruleUrl.replace("{{key}}", encodedKey)
        }
        page?.let { p ->
            val pagePattern = Pattern.compile("\\{\\{\\s*(page(?:[+\\-*/]\\d+)?|\\(page\\s*[-+*/]\\s*\\d+\\)\\s*[-+*/]\\s*\\d+|page\\s*[-+*/]\\s*\\d+)\\s*\\}\\}")
            val matcher = pagePattern.matcher(ruleUrl)
            while (matcher.find()) {
                val expr = matcher.group(1) ?: continue
                val value = evalPageExpr(expr, p).toString()
                ruleUrl = ruleUrl.replace(matcher.group(), value)
            }
        }
    }

    private fun analyzeUrl() {
        val (urlNoOption, option) = UrlOptionParser.split(ruleUrl)
        url = getAbsoluteURL(baseUrl, urlNoOption)
        option?.let(::analyzeFields)
        urlNoQuery = if (url.startsWith("data:", true)) url.substringBefore(",{") else url.substringBefore("?")
        dataBody = decodeDataUrl(url)
    }

    private fun analyzeFields(fieldsTxt: String) {
        val option = UrlOptionParser.parseMap(fieldsTxt)
        if (option != null) {
            option["method"]?.toString()?.takeIf { it.isNotBlank() }?.let { method = it.uppercase() }
            option["body"]?.let { body = valueToString(it) }
            option["charset"]?.toString()?.takeIf { it.isNotBlank() }?.let { charset = it }
            option["headers"]?.let { headerMap.putAll(UrlOptionParser.parseHeaders(it)) }
            option["retry"]?.toString()?.toIntOrNull()?.let { retry = it }
            option["type"]?.toString()?.takeIf { it.isNotBlank() }?.let { type = it }
            val webViewValue = option["webView"] ?: option["useWebView"]
            useWebView = when (webViewValue) {
                null, "", false, "false" -> false
                else -> true
            }
            option["webJs"]?.toString()?.takeIf { it.isNotBlank() }?.let { webJs = it }
            option["bodyJs"]?.toString()?.takeIf { it.isNotBlank() }?.let { bodyJs = it }
            option["dnsIp"]?.toString()?.takeIf { it.isNotBlank() }?.let { dnsIp = it }
            option["dns"]?.toString()?.takeIf { it.isNotBlank() }?.let { dnsIp = it }
            option["proxy"]?.toString()?.takeIf { it.isNotBlank() }?.let { proxy = it }
            option["origin"]?.toString()?.takeIf { it.isNotBlank() }?.let { headerMap["Origin"] = it }
            option["webViewDelayTime"]?.toString()?.toLongOrNull()?.let { webViewDelayTime = max(0, it) }
            option["js"]?.toString()?.takeIf { it.isNotBlank() }?.let { js ->
                evalJS(js, url)?.toString()?.takeIf { it.isNotBlank() }?.let { jsResult ->
                    url = getAbsoluteURL(baseUrl, jsResult)
                }
            }
            urlNoQuery = if (url.startsWith("data:", true)) url.substringBefore(",{") else url.substringBefore("?")
            if (method == "POST" && body != null) {
                val b = body!!
                if (b.trimStart().let { it.startsWith("{") && it.endsWith("}") || it.startsWith("[") && it.endsWith("]") || it.startsWith("<") }) {
                    // JSON/XML body, keep as-is
                } else {
                    encodedForm = b
                }
            }
            return
        }

        if (fieldsTxt.contains("=")) {
            encodedQuery = fieldsTxt
            if (!url.contains("?")) url += "?$encodedQuery" else url += "&$encodedQuery"
        }
    }

    fun execute(): HttpResponse {
        setCookie()
        var lastError: Exception? = null
        repeat(retry + 1) { attempt ->
            try {
                val response = executeOnce()
                saveCookie(response)
                return if (bodyJs != null) {
                    val newBody = evalJS(bodyJs!!, response.body)?.toString() ?: response.body
                    HttpResponse(response.url, newBody, response.code, response.headers)
                } else response
            } catch (e: Exception) {
                lastError = e
                if (attempt < retry) Thread.sleep(300L * (attempt + 1))
            }
        }
        Debug.log("AnalyzeUrl execute error: " + lastError?.message)
        return HttpResponse(url, "", 500)
    }

    private fun executeOnce(): HttpResponse {
        dataBody?.let {
            return if (type != null) {
                HttpResponse(url, it.toByteArray(Charsets.UTF_8).joinToString("") { b -> "%02x".format(b) }, 200)
            } else {
                HttpResponse(url, it, 200)
            }
        }
        return if (useWebView && method != "POST") {
            BackstageWebView.getSource(
                html = null,
                url = url,
                js = webJs,
                headerMap = headerMap,
                delayTime = webViewDelayTime,
                timeout = callTimeout ?: 30000
            )
        } else when (method) {
            "POST" -> {
                val contentType = headerMap.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
                if (!encodedForm.isNullOrBlank()) {
                    HttpClient.postForm(url, encodedForm!!, headerMap, charset, proxy, dnsIp, callTimeout)
                } else if (!body.isNullOrBlank() && !contentType.isNullOrBlank()) {
                    HttpClient.post(url, body!!, headerMap, charset, proxy, dnsIp, callTimeout)
                } else {
                    HttpClient.postJson(url, body ?: "", headerMap, charset, proxy, dnsIp, callTimeout)
                }
            }
            "HEAD" -> HttpClient.head(url, headerMap, proxy, dnsIp, callTimeout)
            else -> HttpClient.get(url, headerMap, charset, proxy, dnsIp, callTimeout)
        }
    }

    fun getStrResponse(jsStr: String? = null, sourceRegex: String? = null): HttpResponse {
        val baseResponse = execute()
        var body = baseResponse.body
        if (!jsStr.isNullOrBlank()) {
            try {
                val jsResult = evalJS(jsStr, body)
                if (jsResult != null && jsResult !is org.mozilla.javascript.Undefined) {
                    body = jsResult.toString()
                }
            } catch (_: Exception) {}
        }
        if (!sourceRegex.isNullOrBlank()) {
            body = try {
                Regex(sourceRegex).find(body)?.value ?: body
            } catch (_: Exception) { body }
        }
        return HttpResponse(baseResponse.url, body, baseResponse.code, baseResponse.headers)
    }

    fun getStrResponse(): HttpResponse = getStrResponse(null, null)

    fun getString(rule: String): String {
        val content = dataBody ?: ruleData?.getVariableValue()?.takeIf { it.isNotEmpty() } ?: return ""
        return runCatching {
            AnalyzeRule(ruleData = ruleData, source = source)
                .setContent(content, baseUrl.ifBlank { url })
                .setChapter(chapter)
                .getString(rule)
        }.getOrDefault("")
    }

    fun getString(rule: String, unescape: Boolean): String = getString(rule)

    fun evalJS(jsStr: String, result: Any? = null): Any? {
        val js = unwrapJs(jsStr)
        val bindings = buildScriptBindings { b ->
            b["java"] = this
            b["cookie"] = CookieStore
            b["cache"] = CacheManager
            b["source"] = source
            b["result"] = result
            b["baseUrl"] = baseUrl
            b["book"] = ruleData as? Book
            b["chapter"] = chapter
            b["key"] = key ?: ""
            b["page"] = page ?: 1
            b["url"] = url
            b["infoMap"] = infoMap
        }
        return try {
            val sharedScope = source?.let { SharedJsScope.getScope(it.jsLib) }
            if (sharedScope != null) bindings.prototype = sharedScope
            val scope = RhinoScriptEngine.getRuntimeScope(bindings)
            RhinoScriptEngine.eval(js, scope)
        } catch (e: Exception) {
            Debug.log("AnalyzeUrl evalJS error: " + e.message)
            null
        }
    }

    private fun setCookie() {
        if (enabledCookieJar) {
            val cookie = CookieStore.getCookie(domain)
            if (cookie.isNotBlank()) headerMap["Cookie"] = cookie
        }
    }

    private fun saveCookie(response: HttpResponse) {
        if (enabledCookieJar) {
            response.headers["set-cookie"]?.forEach { CookieStore.setCookie(domain, it) }
            response.headers["Set-Cookie"]?.forEach { CookieStore.setCookie(domain, it) }
        }
    }

    fun getMethod() = method
    override fun getSource(): BaseSource? = source
    override fun getTag(): String? = source?.getTag()

    fun put(key: String, value: String): String {
        chapter?.putVariable(key, value) ?: ruleData?.putVariable(key, value) ?: source?.put(key, value)
        return value
    }
    fun put(value: String): String {
        chapter?.putVariable(value) ?: ruleData?.putVariable(value) ?: source?.putVariable(value)
        return value
    }
    fun get(key: String): String {
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source?.get(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }
    fun get(): String {
        return chapter?.getVariableValue()?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariableValue()?.takeIf { it.isNotEmpty() }
            ?: source?.getVariable().orEmpty()
    }

    fun isPost(): Boolean = method.equals("POST", true)
    fun getHeaderMap(): Map<String, String> = headerMap
    fun setHeaders(value: String?) { value?.let { headerMap.putAll(UrlOptionParser.parseHeaderLines(it)) } }
    fun getBody(): String? = body
    fun setBody(value: String?) { body = value }
    fun getBodyJs(): String? = bodyJs
    fun setBodyJs(value: String?) { bodyJs = value }
    fun getJs(): String? = webJs
    fun setJs(value: String?) { webJs = value }
    fun getWebJs(): String? = webJs
    fun setWebJs(value: String?) { webJs = value }
    fun useWebView(): Boolean = useWebView
    fun useWebView(boolean: Boolean) { useWebView = boolean }
    fun getOrigin(): String? = headerMap["Origin"]
    fun setOrigin(value: String?) { value?.let { headerMap["Origin"] = it } }
    fun getRetry(): Int = retry
    fun setRetry(value: String?) { retry = value?.toIntOrNull() ?: retry }
    fun getCharset(): String? = charset
    fun setCharset(value: String?) { charset = value }
    fun getDnsIp(): String? = dnsIp
    fun setDnsIp(value: String?) { dnsIp = value }
    fun getServerID(): Long? = null
    fun setServerID(value: String?) { /* no-op: serverID not tracked */ }
    fun getUserAgent(): String = headerMap["User-Agent"] ?: ""
    fun getWebViewDelayTime(): Long = webViewDelayTime
    fun setWebViewDelayTime(value: String?) { webViewDelayTime = value?.toLongOrNull() ?: 0L }
    fun setMethod(value: String?) { value?.let { method = it } }
    suspend fun getStrResponseAwait(jsStr: String? = null, sourceRegex: String? = null): StrResponse { val resp = getStrResponse(jsStr, sourceRegex); return StrResponse(resp) }
    suspend fun getResponseAwait(): HttpResponse = execute()
    fun getErrResponse(e: Throwable): HttpResponse = HttpResponse(url ?: "", e.message ?: "", 500, emptyMap())
    fun getErrStrResponse(e: Throwable): StrResponse = StrResponse(getErrResponse(e))
    suspend fun getByteArrayAwait(): ByteArray { val resp = execute(); return resp.body.toByteArray(java.nio.charset.Charset.forName(charset ?: "UTF-8")) }
    suspend fun getInputStreamAwait(): java.io.InputStream { val resp = execute(); return resp.body.byteInputStream() }
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse { return StrResponse(HttpResponse(url ?: "", "", 501)) }

    companion object {
        private fun unwrapJs(jsStr: String): String {
            return when {
                jsStr.startsWith("@js:", true) -> jsStr.substring(4)
                jsStr.startsWith("<js>", true) && jsStr.lastIndexOf("<") > 0 -> jsStr.substring(4, jsStr.lastIndexOf("<"))
                jsStr.startsWith("@webJs:", true) -> jsStr.substring(7)
                jsStr.startsWith("<webJs>", true) && jsStr.lastIndexOf("<") > 0 -> jsStr.substring(7, jsStr.lastIndexOf("<"))
                else -> jsStr
            }
        }
        fun getAbsoluteURL(base: String, relative: String): String {
            if (relative.startsWith("data:", true)) return relative
            if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
            return try { URL(URL(base), relative).toString() } catch (_: Exception) { relative }
        }
    }

    private fun valueToString(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value
            else -> GSON.toJson(value)
        }
    }

    private fun evalPageExpr(raw: String, page: Int): Int {
        var result = page
        var text = raw.replace(" ", "")
        if (text.startsWith("(") && text.contains(")")) {
            text = text.removePrefix("(").replace(")", "")
        }
        text = text.removePrefix("page")
        val matcher = Pattern.compile("([+\\-*/])(\\d+)").matcher(text)
        while (matcher.find()) {
            val num = matcher.group(2)?.toIntOrNull() ?: continue
            result = when (matcher.group(1)) {
                "+" -> result + num
                "-" -> result - num
                "*" -> result * num
                "/" -> if (num != 0) result / num else result
                else -> result
            }
        }
        return result
    }

    private fun decodeDataUrl(value: String): String? {
        if (!value.startsWith("data:", true)) return null
        return runCatching {
            val main = value.substringBefore(",{")
            val payload = main.substringAfter(",", "")
            if (payload.isBlank()) return@runCatching ""
            val meta = main.substringBefore(",", "")
            if (meta.contains(";base64", true)) {
                String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
            } else {
                java.net.URLDecoder.decode(payload, charset ?: "UTF-8")
            }
        }.getOrNull()
    }

}
