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
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import java.security.SecureRandom

interface JsExtensions {
    fun getSource(): BaseSource?
    fun getTag(): String?

    // ==================== HTTP 请求 ====================

    fun ajax(url: Any): String? {
        return ajax(url, null)
    }

    fun ajax(url: Any, callTimeout: Long?): String? {
        val urlStr = if (url is List<*>) url.firstOrNull()?.toString() ?: "" else url.toString()
        return try {
            val analyzeUrl = AnalyzeUrl(urlStr, source = getSource(), callTimeout = callTimeout)
            analyzeUrl.getStrResponse().body
        } catch (e: Exception) {
            Debug.log("ajax($urlStr) error: ${e.message}")
            null
        }
    }

    fun connect(urlStr: String): StrResponse {
        return try {
            StrResponse(AnalyzeUrl(urlStr, source = getSource()).getStrResponse())
        } catch (e: Exception) {
            StrResponse(HttpResponse(urlStr, e.message ?: "", 0))
        }
    }

    fun connect(urlStr: String, header: String?): StrResponse {
        return connect(urlStr, header, null)
    }

    fun connect(urlStr: String, header: String?, callTimeout: Long?): StrResponse {
        val headerMap = if (header != null) GSON.fromJsonObject<Map<String, String>>(header) else null
        return try {
            StrResponse(AnalyzeUrl(urlStr, headerMapF = headerMap, source = getSource(), callTimeout = callTimeout).getStrResponse())
        } catch (e: Exception) {
            StrResponse(HttpResponse(urlStr, e.message ?: "", 0))
        }
    }

    fun get(urlStr: String, headers: Map<String, String>): Connection.Response = get(urlStr, headers, null)
    fun get(urlStr: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return Jsoup.connect(urlStr).sslSocketFactory(io.legado.engine.http.SSLHelper.unsafeSSLSocketFactory).timeout(timeout ?: 30000).ignoreContentType(true).followRedirects(false).headers(headers).method(Connection.Method.GET).execute()
    }

    fun head(urlStr: String, headers: Map<String, String>): Connection.Response = head(urlStr, headers, null)
    fun head(urlStr: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return Jsoup.connect(urlStr).sslSocketFactory(io.legado.engine.http.SSLHelper.unsafeSSLSocketFactory).timeout(timeout ?: 30000).ignoreContentType(true).followRedirects(false).headers(headers).method(Connection.Method.HEAD).execute()
    }

    fun post(urlStr: String, body: String, headers: Map<String, String>): Connection.Response = post(urlStr, body, headers, null)
    fun post(urlStr: String, body: String, headers: Map<String, String>, timeout: Int?): Connection.Response {
        return Jsoup.connect(urlStr).sslSocketFactory(io.legado.engine.http.SSLHelper.unsafeSSLSocketFactory).timeout(timeout ?: 30000).ignoreContentType(true).followRedirects(false).requestBody(body).headers(headers).method(Connection.Method.POST).execute()
    }

    fun ajaxAll(urlList: Array<String>): Array<StrResponse> = ajaxAll(urlList, false)
    fun ajaxAll(urlList: NativeArray): Array<StrResponse> = ajaxAll(urlList.toStringArray(), false)
    fun ajaxAll(urlList: List<*>): Array<StrResponse> = ajaxAll(urlList.map { it.toString() }.toTypedArray(), false)
    fun ajaxAll(urlList: Any): Array<StrResponse> = ajaxAll(anyToStringArray(urlList), false)
    fun ajaxAll(urlList: Array<String>, skipRateLimit: Boolean): Array<StrResponse> {
        return urlList.map { url ->
            try { StrResponse(AnalyzeUrl(url, source = getSource()).getStrResponse()) }
            catch (e: Exception) { StrResponse(io.legado.engine.http.HttpResponse(url, "", 500)) }
        }.toTypedArray()
    }
    fun ajaxAll(urlList: NativeArray, skipRateLimit: Boolean): Array<StrResponse> = ajaxAll(urlList.toStringArray(), skipRateLimit)
    fun ajaxAll(urlList: List<*>, skipRateLimit: Boolean): Array<StrResponse> = ajaxAll(urlList.map { it.toString() }.toTypedArray(), skipRateLimit)
    fun ajaxAll(urlList: Any, skipRateLimit: Boolean): Array<StrResponse> = ajaxAll(anyToStringArray(urlList), skipRateLimit)
    fun ajaxTestAll(urlList: Array<String>, timeout: Int = 30000): Array<StrResponse> = ajaxTestAll(urlList, timeout, false)
    fun ajaxTestAll(urlList: Array<String>, timeout: Int = 30000, skipRateLimit: Boolean = false): Array<StrResponse> {
        return urlList.map { url ->
            try { StrResponse(AnalyzeUrl(url, source = getSource(), callTimeout = timeout.toLong()).getStrResponse()) }
            catch (e: Exception) { StrResponse(io.legado.engine.http.HttpResponse(url, "", 500)) }
        }.toTypedArray()
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
        return try { String(java.util.Base64.getDecoder().decode(str)) } catch (_: Exception) { str }
    }
    fun base64Decode(str: String, flags: Int): String {
        return try { String(java.util.Base64.getDecoder().decode(str)) } catch (_: Exception) { str }
    }
    fun base64Decode(str: String?, charset: String): String {
        if (str.isNullOrBlank()) return ""
        return try { String(java.util.Base64.getDecoder().decode(str), charset(charset)) } catch (_: Exception) { str }
    }
    fun base64DecodeToByteArray(str: String?): ByteArray? {
        if (str.isNullOrBlank()) return null
        return try { java.util.Base64.getDecoder().decode(str) } catch (_: Exception) { null }
    }
    fun base64DecodeToByteArray(str: String?, flags: Int): ByteArray? {
        if (str.isNullOrBlank()) return null
        return try { java.util.Base64.getDecoder().decode(str) } catch (_: Exception) { null }
    }
    fun base64Encode(str: String): String? = java.util.Base64.getEncoder().encodeToString(str.toByteArray())
    fun base64Encode(str: String, flags: Int): String? = java.util.Base64.getEncoder().encodeToString(str.toByteArray())
    fun base64Encode(bytes: ByteArray): String? = java.util.Base64.getEncoder().encodeToString(bytes)

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
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(data.toByteArray()))
    }
    fun aesDecode(key: String, data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray().copyOf(16), "AES"))
        return String(cipher.doFinal(java.util.Base64.getDecoder().decode(data)))
    }

    fun md5Encode(str: String): String = md5(str)

    fun md5Encode16(str: String): String = md5(str).substring(8, 24)

    fun digestHex(data: String, algorithm: String): String {
        return MessageDigest.getInstance(algorithm).digest(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun digestBase64Str(data: String, algorithm: String): String {
        return java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance(algorithm).digest(data.toByteArray()))
    }

    @Suppress("FunctionName")
    fun HMacHex(data: String, algorithm: String, key: String): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(), algorithm))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    @Suppress("FunctionName")
    fun HMacBase64(data: String, algorithm: String, key: String): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(), algorithm))
        return java.util.Base64.getEncoder().encodeToString(mac.doFinal(data.toByteArray()))
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: ByteArray,
        iv: ByteArray?
    ): SymmetricCryptoHelper {
        return SymmetricCryptoHelper(transformation, key, iv)
    }

    fun createSymmetricCrypto(
        transformation: String,
        key: String,
        iv: String?
    ): SymmetricCryptoHelper {
        return SymmetricCryptoHelper(transformation, key.encodeToByteArray(), iv?.encodeToByteArray())
    }

    fun aesDecodeToString(
        str: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(str)
    }

    fun aesDecodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto(
            "AES/${mode}/${padding}",
            java.util.Base64.getDecoder().decode(key),
            java.util.Base64.getDecoder().decode(iv)
        ).decryptStr(data)
    }

    fun aesEncodeToString(
        str: String, key: String, transformation: String, iv: String
    ): String? {
        return createSymmetricCrypto(transformation, key, iv).encryptBase64(str)
    }

    fun aesEncodeArgsBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto(
            "AES/${mode}/${padding}",
            java.util.Base64.getDecoder().decode(key),
            java.util.Base64.getDecoder().decode(iv)
        ).encryptBase64(data)
    }

    fun tripleDESDecodeStr(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto("DESede/${mode}/${padding}", key, iv).decryptStr(data)
    }

    fun tripleDESEncodeBase64Str(
        data: String,
        key: String,
        mode: String,
        padding: String,
        iv: String
    ): String? {
        return createSymmetricCrypto("DESede/${mode}/${padding}", key, iv).encryptBase64(data)
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
    fun cacheFile(urlStr: String, saveTime: Int): String {
        val key = md5(urlStr).take(16)
        val cached = CacheManager.getString(key)
        if (!cached.isNullOrBlank()) {
            val file = getFile(cached)
            if (file.exists()) return file.readText(Charsets.UTF_8)
        }
        val path = downloadFile(urlStr)
        if (saveTime > 0) CacheManager.putString(key, path, saveTime) else CacheManager.putString(key, path)
        return try { getFile(path).readText(Charsets.UTF_8) } catch (_: Exception) { "" }
    }
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

    fun t2s(text: String): String = text
    fun s2t(text: String): String = text

    // ==================== 主题/配置 ====================

    fun getReadBookConfig(): String = ""
    fun getReadBookConfigMap(): Map<String, Any> = emptyMap()
    fun getThemeConfig(): String = ""
    fun getThemeConfigMap(): Map<String, Any?> = emptyMap()
    fun getThemeMode(): String = "0"

    // ==================== WebView UA ====================

    fun getWebViewUA(): String = try {
        io.legado.engine.shim.AppConfig.userAgent
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

    fun getVerificationCode(imageUrl: String): String {
        val html = "<html><body><img src='${imageUrl}' style='max-width:100%'></body></html>"
        return try {
            val resp = BackstageWebView.getSource(html = html, url = imageUrl, js = null, headerMap = getSource()?.getHeaderMap(true))
            resp.body
        } catch (e: Exception) {
            Debug.log("getVerificationCode error: ${e.message}")
            ""
        }
    }

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
    fun unzipFile(zipPath: String): String {
        val cachePath = AndroidContext.appCtx.cacheDir
        val zipFile = java.io.File(cachePath, zipPath)
        if (!zipFile.exists()) return ""
        val outDir = java.io.File(cachePath, "unzip_" + zipFile.nameWithoutExtension)
        outDir.mkdirs()
        return try {
            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = java.io.File(outDir, entry.name)
                    if (entry.isDirectory) outFile.mkdirs() else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { zis.copyTo(it) }
                    }
                    entry = zis.nextEntry
                }
            }
            outDir.absolutePath
        } catch (e: Exception) {
            Debug.log("unzipFile error: ${e.message}")
            ""
        }
    }
    fun un7zFile(zipPath: String): String = ""
    fun unrarFile(zipPath: String): String = ""
    fun unArchiveFile(zipPath: String): String = unzipFile(zipPath)
    fun getRarStringContent(url: String, path: String): String = ""
    fun getRarStringContent(url: String, path: String, charsetName: String): String = ""
    fun getRarByteArrayContent(url: String, path: String): ByteArray? = null
    fun get7zStringContent(url: String, path: String): String = ""
    fun get7zStringContent(url: String, path: String, charsetName: String): String = ""
    fun get7zByteArrayContent(url: String, path: String): ByteArray? = null
    fun getTxtInFolder(path: String): String {
        if (path.isEmpty()) return ""
        val folder = getFile(path)
        if (!folder.isDirectory) return ""
        val contents = StringBuilder()
        folder.listFiles()?.forEach { f ->
            if (f.isFile) {
                try { contents.appendLine(f.readText(Charsets.UTF_8)) } catch (_: Exception) {}
            }
        }
        return contents.toString().trimEnd()
    }

    // ==================== URL 工具 ====================

    fun isAbsUrl(url: String): Boolean = url.startsWith("http://") || url.startsWith("https://")
    fun isJson(str: String): Boolean = str.trimStart().let { it.startsWith("{") || it.startsWith("[") }
    fun htmlFormat(str: String): String = str
    fun stripUrlOption(url: String): String = UrlOptionParser.strip(url)
    fun toNumChapter(s: String?): String? = s?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }

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
        AndroidContext.androidId()
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
    }
    fun startBrowser(url: String, title: String) {}
    fun startBrowserDp(url: String, title: String) { startBrowser(url, title) }
    fun startBrowser(url: String, title: String, html: String?) {}
    fun startBrowserAwait(url: String, title: String): StrResponse = startBrowserAwait(url, title, false, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse =
        startBrowserAwait(url, title, refetchAfterSuccess, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
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
            String(java.util.Base64.getDecoder().decode(payload), Charsets.UTF_8)
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

    private fun NativeArray.toStringArray(): Array<String> {
        val max = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (0 until max).mapNotNull { index ->
            get(index, this).takeUnless { it is org.mozilla.javascript.Undefined }?.toString()
        }.toTypedArray()
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
 * 解析线程触发登录/设置面板时的宿主回调。
 */
object SourceLoginCallback {
    private var callback: ((source: io.legado.engine.data.BaseSource) -> Unit)? = null
    fun setCallback(cb: (source: io.legado.engine.data.BaseSource) -> Unit) { callback = cb }
    fun requestLogin(source: io.legado.engine.data.BaseSource) { callback?.invoke(source) }
}


    // === Additional JsEncodeUtils methods ===
    fun aesDecodeToByteArray(data: String, key: String, transformation: String, iv: String): ByteArray? {
        return try {
            val parts = transformation.split("/")
            val mode = parts.getOrElse(1) { "CBC" }
            val padding = parts.getOrElse(2) { "PKCS5Padding" }
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            val helper = SymmetricCryptoHelper("AES/$mode/$padding", keyBytes, ivBytes)
            Base64.getDecoder().decode(data)
        } catch (_: Exception) { null }
    }
    fun aesBase64DecodeToByteArray(data: String, key: String, mode: String, padding: String, iv: String): ByteArray? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            SymmetricCryptoHelper("AES/$mode/$padding", keyBytes, ivBytes).decrypt(data)
        } catch (_: Exception) { null }
    }
    fun aesBase64DecodeToString(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try { String(aesBase64DecodeToByteArray(data, key, mode, padding, iv) ?: return null) } catch (_: Exception) { null }
    }
    fun aesEncodeToByteArray(data: String, key: String, transformation: String, iv: String): ByteArray? {
        return try {
            val parts = transformation.split("/")
            val mode = parts.getOrElse(1) { "CBC" }
            val padding = parts.getOrElse(2) { "PKCS5Padding" }
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            SymmetricCryptoHelper("AES/$mode/$padding", keyBytes, ivBytes).encrypt(data)
        } catch (_: Exception) { null }
    }
    fun aesEncodeToBase64ByteArray(data: String, key: String, transformation: String, iv: String): ByteArray? {
        return try { Base64.getEncoder().encode(aesEncodeToByteArray(data, key, transformation, iv) ?: return null) } catch (_: Exception) { null }
    }
    fun aesEncodeToBase64String(data: String, key: String, transformation: String, iv: String): String? {
        return try { Base64.getEncoder().encodeToString(aesEncodeToByteArray(data, key, transformation, iv) ?: return null) } catch (_: Exception) { null }
    }
    fun desDecodeToString(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            String(SymmetricCryptoHelper("DES/$mode/$padding", keyBytes, ivBytes).decrypt(data))
        } catch (_: Exception) { null }
    }
    fun desBase64DecodeToString(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            String(SymmetricCryptoHelper("DES/$mode/$padding", keyBytes, ivBytes).decrypt(Base64.getDecoder().decode(data)))
        } catch (_: Exception) { null }
    }
    fun desEncodeToString(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            String(SymmetricCryptoHelper("DES/$mode/$padding", keyBytes, ivBytes).encrypt(data))
        } catch (_: Exception) { null }
    }
    fun desEncodeToBase64String(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            Base64.getEncoder().encodeToString(SymmetricCryptoHelper("DES/$mode/$padding", keyBytes, ivBytes).encrypt(data))
        } catch (_: Exception) { null }
    }
    fun tripleDESDecodeArgsBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            String(SymmetricCryptoHelper("DESede/$mode/$padding", keyBytes, ivBytes).decrypt(Base64.getDecoder().decode(data)))
        } catch (_: Exception) { null }
    }
    fun tripleDESEncodeArgsBase64Str(data: String, key: String, mode: String, padding: String, iv: String): String? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val ivBytes = if (iv.isNotBlank()) Base64.getDecoder().decode(iv) else null
            Base64.getEncoder().encodeToString(SymmetricCryptoHelper("DESede/$mode/$padding", keyBytes, ivBytes).encrypt(data))
        } catch (_: Exception) { null }
    }
    fun createAsymmetricCrypto(transformation: String, key: String): Any? {
        return try {
            val keyBytes = Base64.getDecoder().decode(key)
            val keySpec = java.security.spec.X509EncodedKeySpec(keyBytes)
            val keyFactory = java.security.KeyFactory.getInstance(transformation.split("/")[0])
            keyFactory.generatePublic(keySpec)
        } catch (_: Exception) { null }
    }
    fun createSign(algorithm: String): java.security.Signature? {
        return try { java.security.Signature.getInstance(algorithm) } catch (_: Exception) { null }
    }

class SymmetricCryptoHelper(
    transformation: String,
    key: ByteArray,
    iv: ByteArray?
) {
    private val cipher: Cipher = Cipher.getInstance(transformation)
    private val keySpec = SecretKeySpec(key, transformation.split("/").first())
    private val ivSpec: IvParameterSpec? = iv?.takeIf { it.isNotEmpty() }?.let { IvParameterSpec(it) }

    fun encrypt(data: ByteArray): ByteArray {
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec ?: run {
            val ivBytes = ByteArray(cipher.blockSize)
            SecureRandom().nextBytes(ivBytes)
            IvParameterSpec(ivBytes)
        })
        return cipher.doFinal(data)
    }

    fun encrypt(data: String): ByteArray = encrypt(data.toByteArray())

    fun decrypt(data: ByteArray): ByteArray {
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec ?: run {
            val ivBytes = ByteArray(cipher.blockSize)
            SecureRandom().nextBytes(ivBytes)
            IvParameterSpec(ivBytes)
        })
        return cipher.doFinal(data)
    }

    fun decrypt(data: String): ByteArray = decrypt(java.util.Base64.getDecoder().decode(data))

    fun decryptStr(data: String): String? {
        return try {
            String(decrypt(data))
        } catch (_: Exception) {
            null
        }
    }

    fun encryptBase64(data: String): String {
        return java.util.Base64.getEncoder().encodeToString(encrypt(data))
    }

    fun encryptHex(data: String): String {
        return encrypt(data).joinToString("") { "%02x".format(it) }
    }
}