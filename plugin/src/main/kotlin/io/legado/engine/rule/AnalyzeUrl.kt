package io.legado.engine.rule

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.engine.constant.AppConst
import io.legado.engine.constant.AppPattern
import io.legado.engine.data.BaseSource
import io.legado.engine.data.Book
import io.legado.engine.data.BookChapter
import io.legado.engine.data.BookSource
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
import io.legado.engine.shim.fromJsonObject
import io.legado.engine.webview.BackstageWebView
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.InputStream
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
    var serverID: Long? = null
        private set
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
            val legacyMatcher = legacyPagePattern.matcher(ruleUrl)
            while (legacyMatcher.find()) {
                val pages = legacyMatcher.group(1)?.split(",").orEmpty()
                if (pages.isNotEmpty()) {
                    val replacement = pages.getOrElse((p - 1).coerceAtLeast(0)) { pages.last() }.trim()
                    ruleUrl = ruleUrl.replace(legacyMatcher.group(), replacement)
                }
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
            option["serverID"]?.toString()?.toLongOrNull()?.let { serverID = it }
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
                    encodedForm = encodeParams(b, charset, false)
                }
            } else if (method != "POST") {
                val pos = url.indexOf('?')
                if (pos >= 0) {
                    analyzeQuery(url.substring(pos + 1))
                    urlNoQuery = url.substring(0, pos)
                }
            }
            return
        }

        if (fieldsTxt.contains("=")) {
            encodedQuery = encodeParams(fieldsTxt, charset, true)
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
        return if (useWebView) {
            val networkResponse = if (method == "POST") executeNetworkOnce() else null
            BackstageWebView.getSource(
                html = networkResponse?.body,
                url = networkResponse?.url ?: requestUrl(),
                js = webJs,
                headerMap = headerMap,
                delayTime = webViewDelayTime,
                timeout = callTimeout ?: 30000
            )
        } else executeNetworkOnce()
    }

    private fun executeNetworkOnce(): HttpResponse {
        return when (method) {
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
            "HEAD" -> HttpClient.head(requestUrl(), headerMap, proxy, dnsIp, callTimeout)
            else -> HttpClient.get(requestUrl(), headerMap, charset, proxy, dnsIp, callTimeout)
        }
    }

    private fun requestUrl(): String {
        return if (!encodedQuery.isNullOrBlank() && urlNoQuery.isNotBlank()) {
            "$urlNoQuery?$encodedQuery"
        } else {
            url
        }
    }

    fun getStrResponse(jsStr: String? = null, sourceRegex: String? = null): HttpResponse {
        val baseResponse = execute()
        var body = baseResponse.body
        if (!jsStr.isNullOrBlank()) {
            try {
                return BackstageWebView.getSource(
                    html = body,
                    url = baseResponse.url,
                    js = unwrapJs(jsStr),
                    headerMap = headerMap,
                    sourceRegex = sourceRegex,
                    delayTime = webViewDelayTime,
                    timeout = callTimeout ?: 30000
                )
            } catch (_: Exception) {}
        }
        if (!sourceRegex.isNullOrBlank()) {
            body = try {
                Regex(sourceRegex).find(body)?.groups?.get(1)?.value
                    ?: Regex(sourceRegex).find(body)?.value
                    ?: body
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
            if (cookie.isNotBlank()) {
                val explicitCookie = headerMap.entries
                    .firstOrNull { it.key.equals("Cookie", true) }
                    ?.value
                headerMap.keys.removeAll { it.equals("Cookie", true) }
                headerMap["Cookie"] = mergeCookies(cookie, explicitCookie)
            }
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
        chapter?.putVariable(key, value); ruleData?.putVariable(key, value); source?.put(key, value)
        return value
    }
    fun put(value: String): String {
        chapter?.putVariable(value); ruleData?.putVariable(value); source?.putVariable(value)
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

    fun getResponse(): HttpResponse = runBlocking { execute() }
    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!url.startsWith("data:", true)) return null
        return decodeDataUrl(url)?.toByteArray()
    }
    fun getByteArray(): ByteArray {
        getByteArrayIfDataUri()?.let { return it }
        return runBlocking { getByteArrayAwait() }
    }
    fun getInputStream(): java.io.InputStream {
        getByteArrayIfDataUri()?.let { return it.inputStream() }
        return runBlocking { getInputStreamAwait() }
    }
    fun getClient(): OkHttpClient = HttpClient.clientFor(proxy, dnsIp, callTimeout)
    fun executeStrRequest(jsStr: String? = null, sourceRegex: String? = null): StrResponse {
        setCookie()
        return try {
            val raw = execute()
            val strResp = StrResponse(raw)
            val loginCheckJs = source?.let { (it as? BookSource)?.loginCheckJs }
            if (!loginCheckJs.isNullOrBlank()) {
                val checkResult = evalJS(loginCheckJs, strResp)
                if (checkResult is StrResponse) checkResult else strResp
            } else strResp
        } catch (e: Exception) {
            StrResponse(HttpResponse(url ?: "", e.message ?: "", 500))
        }
    }
    fun encodeParams(params: String, charset: String?, isQuery: Boolean): String {
        val charsetName = charset?.takeIf { it.isNotBlank() } ?: "UTF-8"
        val escape = charsetName.equals("escape", true)
        val segments = params.split("&")
        return segments.joinToString("&") { part ->
            val index = part.indexOf("=")
            if (index < 0) {
                encodeComponent(part, charsetName, escape, isQuery)
            } else {
                val key = part.substring(0, index)
                val value = part.substring(index + 1)
                encodeComponent(key, charsetName, escape, isQuery) + "=" + encodeComponent(value, charsetName, escape, isQuery)
            }
        }
    }
    private fun encodeComponent(value: String, charsetName: String, escape: Boolean, isQuery: Boolean): String {
        if (value.isEmpty()) return value
        if (percentEncodedPattern.matcher(value).find() && !value.any { it.code > 127 }) return value
        if (escape) return jsEscape(value)
        return try {
            val encoded = URLEncoder.encode(value, charsetName)
            if (isQuery) encoded.replace("+", "%20") else encoded
        } catch (_: Exception) { value }
    }
    private fun jsEscape(value: String): String {
        val keep = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*+-./_@"
        return buildString {
            value.forEach { ch ->
                when {
                    keep.indexOf(ch) >= 0 -> append(ch)
                    ch.code < 256 -> append('%').append(ch.code.toString(16).uppercase().padStart(2, '0'))
                    else -> append("%u").append(ch.code.toString(16).uppercase().padStart(4, '0'))
                }
            }
        }
    }
    fun analyzeQuery(query: String) {
        encodedQuery = encodeParams(query, charset, true)
    }
    fun extractHostFromUrl(url: String): String? {
        return try { java.net.URL(url).host } catch (_: Exception) { null }
    }

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
    fun setServerID(value: String?) { serverID = value?.takeIf { it.isNotBlank() }?.toLongOrNull() }
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
    suspend fun upload(fileName: String, file: Any, contentType: String): StrResponse {
        setCookie()
        val bodyMap = GSON.fromJsonObject<LinkedHashMap<String, Any?>>(body) ?: linkedMapOf()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                bodyMap.forEach { (key, value) ->
                    if (value?.toString() == "fileRequest") {
                        addFormDataPart(key, fileName, file.asRequestBody(contentType))
                    } else {
                        addFormDataPart(key, valueToString(value) ?: "")
                    }
                }
                if (bodyMap.values.none { it?.toString() == "fileRequest" }) {
                    addFormDataPart("file", fileName, file.asRequestBody(contentType))
                }
            }
            .build()
        val request = Request.Builder()
            .url(urlNoQuery.ifBlank { url })
            .post(multipart)
            .apply {
                headerMap.forEach { (key, value) ->
                    if (!key.equals("Content-Type", true)) addHeader(key, value)
                }
            }
            .build()
        val response = getClient().newCall(request).execute()
        val httpResponse = HttpResponse(
            response.request.url.toString(),
            response.body?.string() ?: "",
            response.code,
            response.headers.toCaseInsensitiveMap()
        )
        saveCookie(httpResponse)
        return StrResponse(httpResponse)
    }

    companion object {
        private val legacyPagePattern: Pattern = Pattern.compile("<(.*?)>")
        private val percentEncodedPattern: Pattern = Pattern.compile("%[0-9a-fA-F]{2}")

        private fun mergeCookies(storedCookie: String, explicitCookie: String?): String {
            if (explicitCookie.isNullOrBlank()) return storedCookie
            val cookies = LinkedHashMap<String, String>()
            fun append(header: String) {
                header.split(";")
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.contains("=") }
                    .forEach { part ->
                        val name = part.substringBefore("=").trim()
                        if (name.isNotBlank()) cookies[name] = part
                    }
            }
            append(storedCookie)
            append(explicitCookie)
            return cookies.values.joinToString("; ")
        }

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
            if (relative.startsWith("javascript:", true)) return ""
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

    private fun Any.asRequestBody(contentType: String): RequestBody {
        val mediaType = contentType.toMediaTypeOrNull()
        return when (this) {
            is ByteArray -> this.toRequestBody(mediaType)
            is File -> this.asRequestBody(mediaType)
            is InputStream -> this.readBytes().toRequestBody(mediaType)
            else -> {
                val pathFile = File(toString())
                if (pathFile.exists() && pathFile.isFile) {
                    pathFile.asRequestBody(mediaType)
                } else {
                    toString().toRequestBody(mediaType)
                }
            }
        }
    }

    private fun Headers.toCaseInsensitiveMap(): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        for (name in names()) {
            result.getOrPut(name.lowercase()) { mutableListOf() }.addAll(values(name))
            result.getOrPut(name) { mutableListOf() }.addAll(values(name))
        }
        return result
    }

}
