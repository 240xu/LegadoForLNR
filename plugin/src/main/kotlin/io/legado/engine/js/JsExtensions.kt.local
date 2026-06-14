package io.legado.engine.js

import io.legado.engine.shim.AndroidContext
import io.legado.engine.constant.AppConst
import io.legado.engine.constant.AppPattern
import io.legado.engine.data.BaseSource
import io.legado.engine.http.CookieStore
import io.legado.engine.http.HttpClient
import io.legado.engine.http.StrResponse
import io.legado.engine.http.HttpResponse
import io.legado.engine.rule.AnalyzeUrl
import io.legado.engine.rule.QueryTTF
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.Debug
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject
import io.legado.engine.webview.BackstageWebView
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.mozilla.javascript.NativeArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

private val ajaxAllExecutor = java.util.concurrent.Executors.newFixedThreadPool(
    8.coerceAtMost(Runtime.getRuntime().availableProcessors().coerceAtLeast(2))
) { r -> Thread(r, "legado-ajaxAll").apply { isDaemon = true } }

interface JsExtensions {
    fun getSource(): BaseSource?
    fun getTag(): String?

    // ==================== HTTP 请求 ====================

    fun ajax(url: Any): String? {
        return ajax(url, null)
    }

    fun ajax(url: Any, callTimeout: Long?): String? {
        val urlStr = if (url is List<*>) url.firstOrNull()?.toString() ?: "" else url.toString()
        val src = getSource()
        val bookSrc = src as? io.legado.engine.data.BookSource
        val useCookie = bookSrc?.enabledCookieJar != false
        return try {
            val analyzeUrl = AnalyzeUrl(urlStr, source = src, callTimeout = callTimeout, useCookie = useCookie)
            var response = analyzeUrl.getStrResponse()
            val checkJs = bookSrc?.loginCheckJs
            if (!checkJs.isNullOrBlank()) {
                try {
                    val ar = io.legado.engine.rule.AnalyzeRule(source = src).setContent(response.body, response.url)
                    val checkResult = ar.evalJS(checkJs)
                    if (checkResult == false || checkResult?.toString() == "false") {
                        Debug.log("ajax loginCheckJs failed, attempting login for `${bookSrc?.bookSourceName}")
                        src?.login()
                        response = AnalyzeUrl(urlStr, source = src, callTimeout = callTimeout, useCookie = useCookie).getStrResponse()
                    }
                } catch (_: Exception) {}
            }
            response.body
        } catch (e: Exception) {
            Debug.log("ajax(`$urlStr) error: `${e.message}")
            e.stackTraceToString()
        }
    }

    fun connect(urlStr: String): io.legado.engine.http.StrResponse {
        return try {
            val analyzeUrl = AnalyzeUrl(urlStr, source = getSource())
            io.legado.engine.http.StrResponse(analyzeUrl.execute())
        } catch (e: Exception) {
            io.legado.engine.http.StrResponse(io.legado.engine.http.HttpResponse(urlStr, e.stackTraceToString(), 500))
        }
    }

    fun connect(urlStr: String, header: String?): io.legado.engine.http.StrResponse {
        return try {
            val headerMap = if (header != null) GSON.fromJsonObject<Map<String, String>>(header) ?: emptyMap() else emptyMap()
            val analyzeUrl = AnalyzeUrl(urlStr, source = getSource(), headerMapF = headerMap)
            io.legado.engine.http.StrResponse(analyzeUrl.execute())
        } catch (e: Exception) {
            io.legado.engine.http.StrResponse(io.legado.engine.http.HttpResponse(urlStr, e.stackTraceToString(), 500))
        }
    }

    fun connect(urlStr: String, header: String?, callTimeout: Long?): io.legado.engine.http.StrResponse {
        return try {
            val headerMap = if (header != null) GSON.fromJsonObject<Map<String, String>>(header) ?: emptyMap() else emptyMap()
            val analyzeUrl = AnalyzeUrl(urlStr, source = getSource(), headerMapF = headerMap, callTimeout = callTimeout)
            io.legado.engine.http.StrResponse(analyzeUrl.execute())
        } catch (e: Exception) {
            io.legado.engine.http.StrResponse(io.legado.engine.http.HttpResponse(urlStr, e.stackTraceToString(), 500))
        }
    }

    fun get(urlStr: String, headers: Map<String, String>): io.legado.engine.http.JsoupResponse = get(urlStr, headers, null)
    fun get(urlStr: String, headers: Map<String, String>, timeout: Int?): io.legado.engine.http.JsoupResponse {
        val merged = mergeCookies(urlStr, headers)
        return io.legado.engine.http.JsoupResponse(Jsoup.connect(urlStr).sslSocketFactory(io.legado.engine.http.SSLHelper.unsafeSSLSocketFactory).timeout(timeout ?: 30000).ignoreContentType(true).followRedirects(false).headers(merged).method(Connection.Method.GET).execute())
    }

    fun head(urlStr: String, headers: Map<String, String>): io.legado.engine.http.JsoupResponse = head(urlStr, headers, null)
    fun head(urlStr: String, headers: Map<String, String>, timeout: Int?): io.legado.engine.http.JsoupResponse {
        val merged = mergeCookies(urlStr, headers)
        return io.legado.engine.http.JsoupResponse(Jsoup.connect(urlStr).sslSocketFactory(io.legado.engine.http.SSLHelper.unsafeSSLSocketFactory).timeout(timeout ?: 30000).ignoreContentType(true).followRedirects(false).headers(merged).method(Connection.Method.HEAD).execute())
    }

    fun post(urlStr: String, body: String, headers: Map<String, String>): io.legado.engine.http.JsoupResponse = post(urlStr, body, headers, null)
    fun post(urlStr: String, body: String, headers: Map<String, String>, timeout: Int?): io.legado.engine.http.JsoupResponse {
        val merged = mergeCookies(urlStr, headers)
        return io.legado.engine.http.JsoupResponse(Jsoup.connect(urlStr).sslSocketFactory(io.legado.engine.http.SSLHelper.unsafeSSLSocketFactory).timeout(timeout ?: 30000).ignoreContentType(true).followRedirects(false).requestBody(body).headers(merged).method(Connection.Method.POST).execute())
    }

    fun ajaxAll(urlList: Array<String>): Array<StrResponse> = ajaxAll(urlList, false)
    fun ajaxAll(urlList: NativeArray): Array<StrResponse> = ajaxAll(urlList.toStringArray(), false)
    fun ajaxAll(urlList: List<*>): Array<StrResponse> = ajaxAll(urlList.map { it.toString() }.toTypedArray(), false)
    fun ajaxAll(urlList: Any): Array<StrResponse> = ajaxAll(anyToStringArray(urlList), false)
    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        // 并行执行：skipRateLimit=true时所有请求同时发出，不等待并发率间隔
        // skipRateLimit=false时仍并行但各请求内部会遵循rate limit
        val results = arrayOfNulls<StrResponse>(urlList.size)
        val src = getSource()
        val useCookie = (src as? io.legado.engine.data.BookSource)?.enabledCookieJar != false
        val latch = java.util.concurrent.CountDownLatch(urlList.size)
        val executor = ajaxAllExecutor
        urlList.forEachIndexed { index, url ->
            executor.submit {
                try {
                    results[index] = StrResponse(AnalyzeUrl(url, source = src, useCookie = useCookie).getStrResponse())
                } catch (e: Exception) {
                    results[index] = StrResponse(io.legado.engine.http.HttpResponse(url, "", 500))
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return results as Array<StrResponse>
    }
    fun ajaxAll(urlList: NativeArray, skipRateLimit: Boolean): Array<StrResponse> = ajaxAll(urlList.toStringArray(), skipRateLimit)
    fun ajaxAll(urlList: List<*>, skipRateLimit: Boolean): Array<StrResponse> = ajaxAll(urlList.map { it.toString() }.toTypedArray(), skipRateLimit)
    fun ajaxAll(urlList: Any, skipRateLimit: Boolean): Array<StrResponse> = ajaxAll(anyToStringArray(urlList), skipRateLimit)
    fun ajaxTestAll(urlList: Array<String>, timeout: Int = 30000): Array<StrResponse> = ajaxTestAll(urlList, timeout, false)
    fun ajaxTestAll(urlList: Array<String>, timeout: Int = 30000, skipRateLimit: Boolean = false): Array<StrResponse> {
        val results = arrayOfNulls<StrResponse>(urlList.size)
        val src = getSource()
        val useCookie = (src as? io.legado.engine.data.BookSource)?.enabledCookieJar != false
        val latch = java.util.concurrent.CountDownLatch(urlList.size)
        val executor = ajaxAllExecutor
        urlList.forEachIndexed { index, url ->
            executor.submit {
                try {
                    results[index] = StrResponse(AnalyzeUrl(url, source = src, callTimeout = timeout.toLong(), useCookie = useCookie).getStrResponse())
                } catch (e: Exception) {
                    results[index] = StrResponse(io.legado.engine.http.HttpResponse(url, "", 500))
                } finally {
                    latch.countDown()
                }
            }
        }
        latch.await()
        @Suppress("UNCHECKED_CAST")
        return results as Array<StrResponse>
    }
    fun ajaxTestAll(urlList: NativeArray, timeout: Int = 30000): Array<StrResponse> = ajaxTestAll(urlList.toStringArray(), timeout, false)
    fun ajaxTestAll(urlList: NativeArray, timeout: Int = 30000, skipRateLimit: Boolean = false): Array<StrResponse> =
        ajaxTestAll(urlList.toStringArray(), timeout, skipRateLimit)
    fun ajaxTestAll(urlList: List<*>, timeout: Int = 30000): Array<StrResponse> =
        ajaxTestAll(urlList.map { it.toString() }.toTypedArray(), timeout, false)
    fun ajaxTestAll(urlList: Any, timeout: Int = 30000): Array<StrResponse> = ajaxTestAll(anyToStringArray(urlList), timeout, false)

    // ==================== Cookie 操作 ====================

    fun getCookie(tag: String): String {
        return try { CookieStore.getCookie(java.net.URL(tag).host) } catch (_: Exception) { CookieStore.getCookie(tag) }
    }
    fun getCookie(tag: String, key: String?): String {
        val cookie = getCookie(tag)
        if (key.isNullOrBlank()) return cookie
        return cookie.split(";").map { it.trim() }.firstOrNull { it.startsWith(key + "=") }?.substringAfter("=") ?: ""
    }
    fun getKey(tag: String, key: String): String = CookieStore.getKey(tag, key)

    // ==================== 编码/解码 ====================

    fun base64Decode(str: String?): String {
        if (str.isNullOrBlank()) return ""
        return try { String(android.util.Base64.decode(str, android.util.Base64.DEFAULT)) } catch (_: Exception) { str }
    }
    fun base64Decode(str: String, flags: Int): String {
        return try { String(android.util.Base64.decode(str, flags)) } catch (_: Exception) { str }
    }
    fun base64Decode(str: String?, charset: String): String {
        if (str.isNullOrBlank()) return ""
        return try { String(android.util.Base64.decode(str, android.util.Base64.DEFAULT), charset(charset)) } catch (_: Exception) { str }
    }
    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) return null
        return try { android.util.Base64.decode(str, android.util.Base64.DEFAULT) } catch (_: Exception) { null }
    }
    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) return null
        return try { android.util.Base64.decode(str, flags) } catch (_: Exception) { null }
    }
    fun base64Encode(str: String): String? = android.util.Base64.encodeToString(str.toByteArray(), android.util.Base64.NO_WRAP)
    fun base64Encode(str: String, flags: Int): String? = android.util.Base64.encodeToString(str.toByteArray(), flags)
    fun base64Encode(bytes: ByteArray): String? = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    fun encodeURI(str: String): String = URLEncoder.encode(str, "UTF-8")
    fun encodeURI(str: String, enc: String): String = URLEncoder.encode(str, enc)
    fun encodeURIComponent(str: String): String = URLEncoder.encode(str, "UTF-8").replace("+", "%20").replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~")
    fun decodeURI(str: String): String = URLDecoder.decode(str, "UTF-8")
    fun decodeURIComponent(str: String): String = URLDecoder.decode(str, "UTF-8")

    // ==================== 哈希/加密 ====================

    fun md5(str: String): String = MessageDigest.getInstance("MD5").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    fun md5(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    fun sha1(str: String): String = MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    fun sha256(str: String): String = MessageDigest.getInstance("SHA-256").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }

    fun aesEncode(key: String, data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray().copyOf(16), "AES"))
        return android.util.Base64.encodeToString(cipher.doFinal(data.toByteArray()), android.util.Base64.NO_WRAP)
    }
    fun aesDecode(key: String, data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray().copyOf(16), "AES"))
        return String(cipher.doFinal(android.util.Base64.decode(data, android.util.Base64.DEFAULT)))
    }

    // ==================== 字节操作 ====================

    fun strToBytes(str: String): ByteArray = str.toByteArray()
    fun strToBytes(str: String, charset: String): ByteArray = str.toByteArray(charset(charset))
    fun bytesToStr(bytes: ByteArray): String = String(bytes)
    fun bytesToStr(bytes: ByteArray, charset: String): String = String(bytes, charset(charset))
    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun hexToBytes(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun hexEncodeToString(utf8: String): String? = utf8.toByteArray().joinToString("") { "%02x".format(it) }
    fun hexDecodeToString(hex: String): String? = try { String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) } catch (_: Exception) { null }
    fun hexDecodeToByteArray(hex: String): ByteArray? = try { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (_: Exception) { null }
    fun hexDecodeToString(hex: Any?): String? = hexDecodeToString(hex?.toString() ?: "")

    // ==================== 文件操作 ====================

    fun cacheFile(path: String): java.io.File {
        val cachePath = try { AndroidContext.appCtx.externalCacheDir?.absolutePath ?: "/tmp" } catch (_: Exception) { "/tmp" }
        return java.io.File(cachePath, path)
    }
    fun cacheFile(urlStr: String, saveTime: Int): String = cacheFile(urlStr).absolutePath
    fun downloadFile(url: String): String {
        val file = cacheFile(url)
        file.parentFile?.mkdirs()
        val bytes = HttpClient.getByteArray(url)
        file.writeBytes(bytes)
        return file.absolutePath
    }
    fun downloadFile(content: String, url: String): String {
        val file = cacheFile(url)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.absolutePath
    }
    fun importScript(path: String): String {
        return try {
            if (path.startsWith("http://") || path.startsWith("https://")) {
                HttpClient.get(path).body
            } else {
                val file = cacheFile(path)
                if (file.exists()) file.readText() else ""
            }
        } catch (_: Exception) { "" }
    }
    fun getFile(path: String): java.io.File {
        val cachePath = try { AndroidContext.appCtx.externalCacheDir?.absolutePath ?: "/tmp" } catch (_: Exception) { "/tmp" }
        return java.io.File(cachePath, path)
    }
    fun readFile(path: String): ByteArray? {
        val file = getFile(path)
        return if (file.exists()) file.readBytes() else null
    }
    fun readTxtFile(path: String): String {
        val file = getFile(path)
        return if (file.exists()) file.readText(Charsets.UTF_8) else ""
    }
    fun readTxtFile(path: String, charsetName: String): String {
        val file = getFile(path)
        return if (file.exists()) file.readText(charset(charsetName)) else ""
    }
    fun deleteFile(path: String): Boolean {
        val file = getFile(path)
        return file.deleteRecursively()
    }

    // ==================== 字体处理 ====================

    fun queryTTF(data: Any?): QueryTTF? = queryTTF(data, true)

    fun queryTTF(data: Any?, useCache: Boolean): QueryTTF? {
        val bytes = when (data) {
            is ByteArray -> data
            is String -> queryTTFBytes(data)
            else -> null
        } ?: return null
        val key = sha256(bytes)
        if (useCache) {
            CacheManager.getQueryTTF(key)?.let { return it }
        }
        val queryTTF = QueryTTF(bytes)
        if (useCache) CacheManager.putQueryTTF(key, queryTTF)
        return queryTTF
    }

    fun queryBase64TTF(data: String?): QueryTTF? = queryTTF(data)

    fun replaceFont(text: String, errorTTF: Any?, correctTTF: Any?, filter: Boolean): String {
        val errorQueryTTF = errorTTF as? QueryTTF ?: return text
        val correctQueryTTF = correctTTF as? QueryTTF ?: return text
        val codePoints = text.codePoints().toArray()
        val builder = StringBuilder(text.length)
        for (codePoint in codePoints) {
            if (errorQueryTTF.isBlankUnicode(codePoint)) {
                builder.appendCodePoint(codePoint)
                continue
            }
            var glyf = errorQueryTTF.getGlyfByUnicode(codePoint)
            if (errorQueryTTF.getGlyfIdByUnicode(codePoint) == 0) glyf = null
            if (filter && glyf == null) continue
            val corrected = if (glyf == null) 0 else correctQueryTTF.getUnicodeByGlyf(glyf)
            if (corrected != 0) builder.appendCodePoint(corrected) else builder.appendCodePoint(codePoint)
        }
        return builder.toString()
    }

    fun replaceFont(text: String, errorTTF: Any?, correctTTF: Any?): String =
        replaceFont(text, errorTTF, correctTTF, false)

    // ==================== 中文转换 ====================

    fun t2s(text: String): String = try {
        // Android内置ICU Transliterator，繁体→简体
        android.icu.text.Transliterator.getInstance("Traditional-Simplified").transliterate(text)
    } catch (_: Exception) {
        // 回退：使用内置常见繁简映射
        t2sFallback(text)
    }

    fun s2t(text: String): String = try {
        android.icu.text.Transliterator.getInstance("Simplified-Traditional").transliterate(text)
    } catch (_: Exception) {
        s2tFallback(text)
    }

    // ==================== 主题/配置 ====================

    fun getReadBookConfig(): String = ""
    fun getReadBookConfigMap(): Map<String, Any> = emptyMap()
    fun getThemeConfig(): String = ""
    fun getThemeConfigMap(): Map<String, Any?> = emptyMap()
    fun getThemeMode(): String = "0"

    // ==================== WebView UA ====================

    fun getWebViewUA(): String = try {
        android.webkit.WebSettings.getDefaultUserAgent(AndroidContext.appCtx)
    } catch (_: Exception) { AppConst.USER_AGENT }

    // ==================== 打开页面 (useweb discovery) ====================

    fun open(type: String, url: String, title: String) {
        Debug.log("java.open($type, $url, $title) - 由LNR宿主处理")
        SourceOpenCallback.onOpen(type, url, title)
    }

    fun openExplore(url: String, title: String) { open("explore", url, title) }
    fun openSearch(key: String, title: String) { open("search", key, title) }

    // ==================== WebView ====================

    fun webView(html: String?, url: String?, js: String?): String? = webView(html, url, js, false)
    fun webView(html: String?, url: String?, js: String?, cacheFirst: Boolean): String? {
        return BackstageWebView.getSource(
            html = html,
            url = url,
            js = js,
            headerMap = getSource()?.getHeaderMap(true)
        ).body
    }
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String): String? =
        webViewGetSource(html, url, js, sourceRegex, false, 0)
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean): String? =
        webViewGetSource(html, url, js, sourceRegex, cacheFirst, 0)
    fun webViewGetSource(html: String?, url: String?, js: String?, sourceRegex: String, cacheFirst: Boolean, timeout: Long): String? {
        return BackstageWebView.getSource(
            html = html,
            url = url,
            js = js,
            headerMap = getSource()?.getHeaderMap(true),
            sourceRegex = sourceRegex,
            delayTime = timeout
        ).body
    }
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String): String? =
        webViewGetOverrideUrl(html, url, js, overrideUrlRegex, false, 0)
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean): String? =
        webViewGetOverrideUrl(html, url, js, overrideUrlRegex, cacheFirst, 0)
    fun webViewGetOverrideUrl(html: String?, url: String?, js: String?, overrideUrlRegex: String, cacheFirst: Boolean, timeout: Long): String? {
        return BackstageWebView.getSource(
            html = html,
            url = url,
            js = js,
            headerMap = getSource()?.getHeaderMap(true),
            overrideUrlRegex = overrideUrlRegex,
            delayTime = timeout
        ).body
    }

    // ==================== 验证码 ====================

    fun getVerificationCode(imageUrl: String): String = ""

    // ==================== ZIP ====================

    fun getZipStringContent(url: String, path: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, Charsets.UTF_8)
    }
    fun getZipStringContent(url: String, path: String, charsetName: String): String {
        val byteArray = getZipByteArrayContent(url, path) ?: return ""
        return String(byteArray, charset(charsetName))
    }
    fun getZipByteArrayContent(url: String, path: String): ByteArray? {
        val bytes = if (url.startsWith("http://") || url.startsWith("https://")) {
            try { HttpClient.getByteArray(url) } catch (_: Exception) { return null }
        } else { try { url.chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (_: Exception) { return null } }
        val bos = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (entry!!.name == path) { zis.copyTo(bos); return bos.toByteArray() }
                entry = zis.nextEntry
            }
        }
        return null
    }
    fun unzipFile(zipPath: String): String = ""
    fun un7zFile(zipPath: String): String = ""
    fun unrarFile(zipPath: String): String = ""
    fun unArchiveFile(zipPath: String): String = ""
    fun getRarStringContent(url: String, path: String): String = ""
    fun getRarStringContent(url: String, path: String, charsetName: String): String = ""
    fun getRarByteArrayContent(url: String, path: String): ByteArray? = null
    fun get7zStringContent(url: String, path: String): String = ""
    fun get7zStringContent(url: String, path: String, charsetName: String): String = ""
    fun get7zByteArrayContent(url: String, path: String): ByteArray? = null
    fun getTxtInFolder(path: String): String = ""

    // ==================== URL 工具 ====================

    fun isAbsUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")
    fun isJson(str: String): Boolean = str.trimStart().let { it.startsWith("{") || it.startsWith("[") }
    fun htmlFormat(str: String): String {
        return try {
            val doc = org.jsoup.Jsoup.parseBodyFragment(str)
            doc.select("img").forEach { it.replaceWith(org.jsoup.nodes.TextNode("[[${it.attr("src")}]]")) }
            doc.body().text().replace("[[", "<img src=\"").replace("]]", "\">")
        } catch (_: Exception) { str }
    }
    fun stripUrlOption(url: String): String = UrlOptionParser.strip(url)
    fun toNumChapter(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val matcher = io.legado.engine.constant.AppPattern.titleNumPattern.matcher(s)
        if (matcher.find()) {
            val intStr = matcher.group(2)?.toIntOrNull()?.toString() ?: matcher.group(2)
            return "${matcher.group(1)}${intStr}${matcher.group(3)}"
        }
        return s
    }

    class JsURL(urlStr: String, baseUrl: String? = null) {
        var url: String = urlStr
        var host: String = ""
        var path: String = ""
        var query: String = ""
        init {
            try {
                val base = if (baseUrl != null) java.net.URL(baseUrl) else null
                val u = if (base != null) java.net.URL(base, urlStr) else java.net.URL(urlStr)
                url = u.toString(); host = u.host; path = u.path; query = u.query ?: ""
            } catch (_: Exception) { url = urlStr; host = ""; path = urlStr; query = "" }
        }
        override fun toString(): String = url
    }
    fun toURL(urlStr: String): JsURL = JsURL(urlStr)
    fun toURL(url: String, baseUrl: String? = null): JsURL = JsURL(url, baseUrl)

    // ==================== 日志 ====================

    fun log(msg: Any?): Any? { Debug.log(msg?.toString() ?: "null"); return msg }
    fun logType(any: Any?) { Debug.log("${any?.javaClass?.simpleName}: $any") }
    fun toast(msg: Any?) { Debug.log("toast: ${msg ?: "null"}") }
    fun longToast(msg: Any?) { Debug.log("longToast: ${msg ?: "null"}") }

    // ==================== 设备 ====================

    fun randomUUID(): String = UUID.randomUUID().toString()
    fun androidId(): String = try {
        android.provider.Settings.Secure.getString(AndroidContext.appCtx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
    } catch (_: Exception) { "unknown" }
    fun qread(): String = "0"
    fun deviceID(): String = androidId()
    fun currentTimeMillis(): Long = System.currentTimeMillis()
    fun timeFormat(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
    fun timeFormat(pattern: String): String = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
    fun timeFormatUTC(time: Long, format: String, sh: Int): String? = try { SimpleDateFormat(format, Locale.getDefault()).format(Date(time + sh * 3600000L)) } catch (_: Exception) { null }

    fun str(obj: Any?): String = obj?.toString() ?: ""

    // ==================== Browser ====================

    fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        Debug.log("showBrowser: $url")
        SourceBrowserCallback.onShow(url, html, preloadJs, false)
    }
    fun startBrowser(url: String, title: String) {
        SourceBrowserCallback.onShow(url, null, null, false)
    }
    fun startBrowserDp(url: String, title: String) { startBrowser(url, title) }
    fun startBrowser(url: String, title: String, html: String?) {
        SourceBrowserCallback.onShow(url, html, null, false)
    }
    fun startBrowserAwait(url: String, title: String): StrResponse = startBrowserAwait(url, title, false, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse =
        startBrowserAwait(url, title, refetchAfterSuccess, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
        val callbackResult = SourceBrowserCallback.onShowAwait(url, html, null)
        if (callbackResult != null) return callbackResult
        return try {
            val body = when {
                !html.isNullOrBlank() -> html
                url.startsWith("data:text/html", true) -> decodeDataText(url)
                url.startsWith("http://") || url.startsWith("https://") -> HttpClient.get(url, getSource()?.getHeaderMap(true) ?: emptyMap()).body
                else -> ""
            }
            StrResponse(HttpResponse(url, body, 200))
        } catch (e: Exception) {
            Debug.log("startBrowserAwait($url) error: ${e.message}")
            StrResponse(HttpResponse(url, "", 500))
        }
    }

    // ==================== Video ====================

    fun openUrl(url: String) { Debug.log("openUrl: $url") }
    fun openUrl(url: String, mimeType: String? = null) { openUrl(url) }
    fun openVideoPlayer(url: String, title: String) {}
    fun openVideoPlayer(url: String, title: String, isFloat: Boolean) {}

    private fun queryTTFBytes(value: String): ByteArray? {
        return when {
            value.startsWith("http://") || value.startsWith("https://") ->
                runCatching { HttpClient.getByteArray(value, getSource()?.getHeaderMap(true) ?: emptyMap()) }.getOrNull()
            value.startsWith("file://") ->
                runCatching { java.io.File(java.net.URI(value)).readBytes() }.getOrNull()
            java.io.File(value).exists() ->
                runCatching { java.io.File(value).readBytes() }.getOrNull()
            else -> base64DecodeToByteArray(value)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun decodeDataText(url: String): String {
        val payload = url.substringAfter(",", "")
        if (payload.isBlank()) return ""
        return if (url.substringBefore(",", "").contains(";base64", true)) {
            String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } else {
            URLDecoder.decode(payload, "UTF-8")
        }
    }

    private fun anyToStringArray(value: Any): Array<String> {
        return when (value) {
            is Array<*> -> value.map { it.toString() }.toTypedArray()
            is List<*> -> value.map { it.toString() }.toTypedArray()
            is NativeArray -> value.toStringArray()
            else -> arrayOf(value.toString())
        }
    }

    /** 将CookieStore中对应域名的Cookie合并到headers中 */
    private fun mergeCookies(urlStr: String, headers: Map<String, String>): Map<String, String> {
        if (headers.containsKey("Cookie") || headers.containsKey("cookie")) return headers
        val host = try { java.net.URL(urlStr).host } catch (_: Exception) { return headers }
        val cookie = CookieStore.getCookie(host)
        if (cookie.isBlank()) return headers
        return headers.toMutableMap().apply { put("Cookie", cookie) }
    }

    private fun NativeArray.toStringArray(): Array<String> {
        val max = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (0 until max).mapNotNull { index ->
            get(index, this).takeUnless { it is org.mozilla.javascript.Undefined }?.toString()
        }.toTypedArray()
    }

    // 繁→简 内置回退映射（最常用300+字）
    private fun t2sFallback(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(T2S_MAP.getOrDefault(ch, ch))
        }
        return sb.toString()
    }

    // 简→繁 回退：反转映射
    private fun s2tFallback(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(S2T_MAP.getOrDefault(ch, ch))
        }
        return sb.toString()
    }

    companion object T2SMapping {
        // 繁→简 映射表
        private val T2S_MAP: Map<Char, Char> by lazy {
            val traditional = "東車馬書門開關長飛風雲電霧氣學對歡觀歡買賣實體義議記設許話說讀請談講誰護豐麗來個們備償優兒蘭鳳剛創劃劇劉劍動務區醫協壓縣發嘆團圖圓夢歲幣廠廣慶應態懷戰戲戶掃擔擁擠撥換揮損搶據擇擊數斷於時曆書會機權條楊業極構樂歷歸殺樣橋檔檢歐殘毀歲歷濟為無煉燈燒營爺爾牆獻獨獎獲現瑤環異畫當療發盡監盤確認禍種穩節約結絕統綜綠經練給維網縣線織總績續繕罰義習聖聞聯職聽肅華萬葉號補裝複裡製複觀規覺覽訊記訴診詞試詢該詳認誕語誤說請諸諾課談論誰調議護變讓讚豐豬貓貝貞負財貨責費質賴贈趕車軍軟較載輩輝辦邊達選遲郵鄰醫釋鋪鏡鐘鐵鑽陣險隨隱難靈靜響頁頂順須顧顯飛飾飽餓首馬駭驗麗黃點齊齒齡龍龜"
            val simplified = "东车马书门开关长飞风云电雾气学对欢观欢买卖实义议记设许话说读请谈讲谁护丰丽来个们备偿优儿兰凤刚创划剧刘剑动务区医协压县发叹团图圆梦币厂广庆应态怀战戏户扫担拥拨换挥损抢据择击数断于时历书会机权条杨业极构乐历归杀样桥档检欧残毁历济为无炼灯烧营尔墙献独奖获现环异画当疗发尽监盘确认祸种稳节约结绝统综绿经练给维网县线织总绩续罚习圣闻联职听肃华万叶号补装复里制复观规觉览讯记诉诊词试询该详认诞语误说请诸诺课谈论谁调议护变让赞丰猪猫贝贞负财货责费质赖赠赶军软较载辈辉办边达选迟邮邻医释铺镜钟铁钻阵险随隐难灵静响页顶须顾显飞饰饱饿首马验丽黄点齐齿龄龙龟"
            require(traditional.length == simplified.length) { "T2S mapping length mismatch" }
            traditional.zip(simplified).toMap()
        }

        // 简→繁 映射表（反转）
        private val S2T_MAP: Map<Char, Char> by lazy {
            T2S_MAP.entries.associate { (k, v) -> v to k }
        }
    }
}

/**
 * java.open() 回调接口
 */
object SourceOpenCallback {
    private var callback: ((String, String, String) -> Unit)? = null
    fun setCallback(cb: (String, String, String) -> Unit) { callback = cb }
    fun onOpen(type: String, url: String, title: String) { callback?.invoke(type, url, title) }
}

/**
 * 浏览器Dialog回调，由插件层注册实现
 */
object SourceBrowserCallback {
    private var showCallback: ((String, String?, String?, Boolean) -> Unit)? = null
    private var awaitCallback: ((String, String?, String?) -> StrResponse?)? = null

    fun setShowCallback(cb: (url: String, html: String?, preloadJs: String?, await: Boolean) -> Unit) { showCallback = cb }
    fun setAwaitCallback(cb: (url: String, html: String?, preloadJs: String?) -> StrResponse?) { awaitCallback = cb }

    fun onShow(url: String, html: String?, preloadJs: String?, await: Boolean) {
        showCallback?.invoke(url, html, preloadJs, await)
    }
    fun onShowAwait(url: String, html: String?, preloadJs: String?): StrResponse? {
        return awaitCallback?.invoke(url, html, preloadJs)
    }
}

/**
 * 解析线程触发登录/设置面板时的宿主回调。
 */
object SourceLoginCallback {
    private var callback: ((source: io.legado.engine.data.BaseSource) -> Unit)? = null
    fun setCallback(cb: (source: io.legado.engine.data.BaseSource) -> Unit) { callback = cb }
    fun requestLogin(source: io.legado.engine.data.BaseSource) { callback?.invoke(source) }
}
