package io.legado.plugin

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.legado.engine.data.BookSource
import io.legado.engine.http.CookieStore
import io.legado.engine.http.HttpClient
import io.legado.engine.http.HttpResponse
import io.legado.engine.http.StrResponse
import io.legado.engine.model.RowUi
import io.legado.engine.rule.AnalyzeUrl
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.CacheManager
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Undefined
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 登录 JS 桥接
 * 在 loginUrl / loginUi 的 JS 中通过 java.xxx() 调用
 *
 * 对应 Legado 的 SourceLoginJsExtensions
 */
class LoginJsBridge(
    activity: Activity? = null,
    private val sourceUrl: String,
    private val callback: Callback? = null,
    private val bookSource: BookSource? = null
) {
    private val activityRef: WeakReference<Activity> = WeakReference(activity)

    /** 登录数据 (表单提交时 result 变量) */
    var loginData: MutableMap<String, String> = mutableMapOf()

    val bookSourceUrl: String get() = bookSource?.bookSourceUrl ?: sourceUrl
    val bookSourceName: String get() = bookSource?.bookSourceName ?: sourceUrl
    val bookSourceComment: String? get() = bookSource?.bookSourceComment
    val variableComment: String? get() = bookSource?.variableComment
    var loginUrl: String?
        get() = bookSource?.loginUrl
        set(value) { bookSource?.loginUrl = value }
    var loginUi: String?
        get() = bookSource?.loginUi
        set(value) { bookSource?.loginUi = value }

    interface Callback {
        fun upLoginData(data: Map<String, Any?>?)
        fun reLoginView(deltaUp: Boolean)
    }

    // ==================== source 对象方法 ====================

    /** source.getKey() */
    fun getKey(): String = sourceUrl

    /** source.getTag() */
    fun getTag(): String = sourceUrl

    /** source.login() - 调用登录函数 */
    fun login() {
        // 在 LoginActivity 中通过 Rhino 执行 login()
    }

    /** source.getLoginInfo() — 使用AES解密，与BaseSource一致 */
    fun getLoginInfo(): String? {
        return try {
            CacheManager.get("userInfo_$sourceUrl")?.let { cache ->
                return try { io.legado.engine.data.BaseSource.decryptAes(cache) } catch (_: Exception) { cache }
            }
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_info", android.content.Context.MODE_PRIVATE)
            prefs?.getString("info_$sourceUrl", null)
        } catch (_: Exception) { null }
    }

    /** source.getLoginInfoMap() — 解析loginUi默认值 */
    fun getLoginInfoMap(): MutableMap<String, String> {
        val json = getLoginInfo()
        if (json != null) {
            return try {
                com.google.gson.Gson().fromJson(json, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type) ?: mutableMapOf()
            } catch (_: Exception) { mutableMapOf() }
        }
        // 从loginUi解析默认字段
        if (loginUi.isNullOrBlank()) return mutableMapOf()
        val resolved = if (loginUi!!.startsWith("@js:", true) || loginUi!!.startsWith("<js>", true)) {
            val src = bookSource ?: return mutableMapOf()
            try { src.evalJS(io.legado.engine.constant.AppPattern.stripJsPrefix(loginUi!!))?.toString() } catch (_: Exception) { loginUi }
        } else loginUi
        val defaults = com.google.gson.Gson().fromJson<List<io.legado.engine.model.RowUi>>(resolved, object : com.google.gson.reflect.TypeToken<List<io.legado.engine.model.RowUi>>() {}.type)
            ?.filter { it.type != io.legado.engine.model.RowUi.Type.button }
            ?.associate { it.name to (it.default ?: "") }
            ?.filterKeys { it.isNotBlank() }
            ?: emptyMap()
        if (defaults.isNotEmpty()) putLoginInfo(com.google.gson.Gson().toJson(defaults))
        return defaults.toMutableMap()
    }

    /** source.putLoginInfo() — 使用AES加密，与BaseSource一致 */
    fun putLoginInfo(info: String): Boolean {
        return try {
            val encoded = io.legado.engine.data.BaseSource.encryptAes(info)
            CacheManager.put("userInfo_$sourceUrl", encoded)
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_info", android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.putString("info_$sourceUrl", info)?.apply()
            true
        } catch (_: Exception) { false }
    }

    /** source.removeLoginInfo() */
    fun removeLoginInfo() {
        try {
            CacheManager.delete("userInfo_$sourceUrl")
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_info", android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.remove("info_$sourceUrl")?.apply()
        } catch (_: Exception) {}
    }

    /** source.putLoginHeader() */
    fun putLoginHeader(header: String) {
        val headerMap = try {
            com.google.gson.Gson().fromJson<Map<String, String>>(header, object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type)
        } catch (_: Exception) { null }
        headerMap?.get("Cookie")?.let { CookieStore.replaceCookie(sourceUrl, it) }
        try {
            CacheManager.put("loginHeader_$sourceUrl", header)
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_info", android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.putString("loginHeader_$sourceUrl", header)?.apply()
        } catch (_: Exception) {}
    }

    /** source.getLoginHeader() */
    fun getLoginHeader(): String? {
        return try {
            CacheManager.get("loginHeader_$sourceUrl")?.let { return it }
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_info", android.content.Context.MODE_PRIVATE)
            prefs?.getString("loginHeader_$sourceUrl", null)
        } catch (_: Exception) { null }
    }

    /** source.removeLoginHeader() */
    fun removeLoginHeader() {
        try {
            CacheManager.delete("loginHeader_$sourceUrl")
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_info", android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.remove("loginHeader_$sourceUrl")?.apply()
        } catch (_: Exception) {}
        CookieStore.removeCookie(sourceUrl)
    }

    /** source.getHeaderMap() - 返回 source header + loginHeader + UA */
    fun getHeaderMap(): Map<String, String> {
        val merged = mutableMapOf<String, String>()
        bookSource?.getHeaderMap(true)?.let { merged.putAll(it) }
        if (!merged.containsKey("User-Agent") && !merged.containsKey("user-agent")) {
            merged["User-Agent"] = io.legado.engine.constant.AppConst.USER_AGENT
        }
        return merged
    }

    /** source.getVariable() / source.putVariable() */
    fun getVariable(): String = CacheManager.get("sourceVariable_$sourceUrl") ?: ""
    fun getVariable(key: String): String = get(key)
    fun setVariable(value: String?) {
        if (value != null) CacheManager.put("sourceVariable_$sourceUrl", value)
        else CacheManager.delete("sourceVariable_$sourceUrl")
    }
    fun putVariable(value: String?): String {
        if (value != null) setVariable(value)
        return value ?: ""
    }
    fun putVariable(key: String, value: String): String = put(key, value)


    // ==================== AnalyzeUrl 相关（登录 JS 中通过 java. 调用） ====================

    private var currentAnalyzeUrl: AnalyzeUrl? = null

    /**
     * 重新解析 url，用于登录检测 JS 登录后重新解析 url 重新访问。
     * 对应 Legado java.initUrl()
     */
    fun initUrl() {
        currentAnalyzeUrl?.initUrl()
    }

    /**
     * 返回访问结果（文本类型），书源内部重新登录后可调用此方法重新返回结果。
     * 对应 Legado java.getStrResponse()
     */
    fun getStrResponse(): String {
        return try {
            val url = currentAnalyzeUrl ?: AnalyzeUrl(loginUrl ?: sourceUrl, source = bookSource)
            url.getStrResponse().body
        } catch (e: Exception) {
            android.util.Log.e("LoginJsBridge", "getStrResponse error", e)
            ""
        }
    }

    /**
     * 返回访问结果（HttpResponse），调用登录后在调用这方法可以重新访问。
     * 对应 Legado java.getResponse()
     */
    fun getResponse(): io.legado.engine.http.HttpResponse {
        return try {
            val url = currentAnalyzeUrl ?: AnalyzeUrl(loginUrl ?: sourceUrl, source = bookSource)
            url.getStrResponse()
        } catch (e: Exception) {
            android.util.Log.e("LoginJsBridge", "getResponse error", e)
            io.legado.engine.http.HttpResponse(sourceUrl, "", 500)
        }
    }

    // ==================== HTTP 请求 ====================

    fun ajax(url: Any): String {
        val urlStr = url.toString()
        return try { AnalyzeUrl(urlStr).getStrResponse().body } catch (e: Exception) { "ajax error: ${e.message}" }
    }

    fun ajax(url: Any, headers: Any?): String {
        val urlStr = url.toString()
        val headerMap = parseHeaders(headers)
        return try { HttpClient.get(urlStr, headerMap).body } catch (e: Exception) { "ajax error: ${e.message}" }
    }

    fun post(url: Any, body: Any?): String {
        val urlStr = url.toString()
        val bodyStr = body?.toString() ?: ""
        return try { HttpClient.post(urlStr, bodyStr).body } catch (e: Exception) { "post error: ${e.message}" }
    }

    fun post(url: Any, body: Any?, headers: Any?): String {
        val urlStr = url.toString()
        val bodyStr = body?.toString() ?: ""
        val headerMap = parseHeaders(headers)
        return try {
            val merged = mutableMapOf<String, String>()
            merged["Content-Type"] = "application/x-www-form-urlencoded"
            merged.putAll(headerMap)
            HttpClient.post(urlStr, bodyStr, merged).body
        } catch (e: Exception) { "post error: ${e.message}" }
    }

    // ==================== Cookie 操作 ====================

    fun getCookie(url: String): String {
        val domain = try { java.net.URL(url).host } catch (_: Exception) { url }
        return CookieStore.getCookie(domain)
    }

    fun setCookie(url: String, cookie: String) {
        CookieStore.setCookieFromUrl(url, cookie)
    }
    fun getKey(tag: String, key: String): String = CookieStore.getKey(tag, key)
    fun removeCookie(key: String) = CookieStore.removeCookie(key)

    // ==================== 登录 UI 回调 ====================

    fun upLoginData(data: Any?) {
        val map = data.toAnyMap()
        activityRef.get()?.runOnUiThread { callback?.upLoginData(map) }
    }

    fun reLoginView() { reLoginView(false) }
    fun reLoginView(deltaUp: Boolean) {
        activityRef.get()?.runOnUiThread { callback?.reLoginView(deltaUp) }
    }

    // ==================== 工具方法 ====================

    fun toast(msg: Any?) {
        activityRef.get()?.runOnUiThread { Toast.makeText(activityRef.get(), msg?.toString() ?: "", Toast.LENGTH_SHORT).show() }
    }
    fun longToast(msg: Any?) {
        activityRef.get()?.runOnUiThread { Toast.makeText(activityRef.get(), msg?.toString() ?: "", Toast.LENGTH_LONG).show() }
    }
    fun log(msg: Any?) { android.util.Log.d("LegadoLogin", msg?.toString() ?: "null") }

    fun base64Encode(str: String): String = java.util.Base64.getEncoder().encodeToString(str.toByteArray())
    fun base64Encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
    fun base64Decode(str: String): String = try { String(java.util.Base64.getDecoder().decode(str)) } catch (_: Exception) { str }
    fun md5(str: String): String = java.security.MessageDigest.getInstance("MD5").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    fun sha1(str: String): String = java.security.MessageDigest.getInstance("SHA-1").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    fun sha256(str: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    fun urlEncode(str: String): String = java.net.URLEncoder.encode(str, "UTF-8")
    fun urlDecode(str: String): String = java.net.URLDecoder.decode(str, "UTF-8")
    fun hexEncode(str: String): String = str.toByteArray().joinToString("") { "%02x".format(it) }
    fun hexDecode(hex: String): String = try { String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) } catch (_: Exception) { hex }
    fun hexDecodeToString(hex: Any?): String = hexDecode(hex?.toString() ?: "")

    /** 获取登录数据 (对应 Legado 中的 result 变量) */
    @android.webkit.JavascriptInterface
    fun fetchLoginData(): MutableMap<String, String> = loginData

    fun randomUUID(): String = java.util.UUID.randomUUID().toString()
    fun androidId(): String = "legado_lnr_plugin"


    // ==================== SourceLoginJsExtensions 补充方法 ====================

    /** 刷新发现页 */
    fun refreshExplore() {
        activityRef.get()?.runOnUiThread { callback?.reLoginView(false) }
    }

    /** 刷新书籍信息 */
    fun refreshBookInfo() {
        android.util.Log.d("LegadoLogin", "refreshBookInfo called")
    }

    /** 刷新目录 */
    fun refreshBookToc() {
        android.util.Log.d("LegadoLogin", "refreshBookToc called")
    }

    /** 刷新正文 */
    fun refreshContent() {
        android.util.Log.d("LegadoLogin", "refreshContent called")
    }

    /** 复制文本到剪贴板 */
    fun copyText(text: String) {
        activityRef.get()?.runOnUiThread {
            val clipboard = activityRef.get()?.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("legado", text))
            toast("已复制到剪贴板")
        }
    }

    /** 显示内置浏览器 */
    fun showBrowser(url: String, html: String? = null, preloadJs: String? = null, config: String? = null) {
        val activity = activityRef.get() ?: return
        io.legado.engine.webview.BrowserDialogHelper.open(
            activity, url, "浏览器", html, preloadJs,
            jsInterface = this, jsInterfaceName = "java"
        )
    }
    fun startBrowser(url: String, title: String) {
        val activity = activityRef.get() ?: return
        io.legado.engine.webview.BrowserDialogHelper.open(
            activity, url, title, null, null,
            jsInterface = this, jsInterfaceName = "java"
        )
    }
    fun startBrowserDp(url: String, title: String) = startBrowser(url, title)
    fun openUrl(url: String) = startBrowser(url, url)
    fun openUrl(url: String, mimeType: String?) = startBrowser(url, url)
    fun startBrowserAwait(url: String, title: String): StrResponse = startBrowserAwait(url, title, false, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean): StrResponse =
        startBrowserAwait(url, title, refetchAfterSuccess, null)
    fun startBrowserAwait(url: String, title: String, refetchAfterSuccess: Boolean, html: String?): StrResponse {
        val activity = activityRef.get() ?: return fallbackBrowserResponse(url, html)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            io.legado.engine.webview.BrowserDialogHelper.open(
                activity, url, title, html, null,
                jsInterface = this, jsInterfaceName = "java"
            )
            return fallbackBrowserResponse(url, html)
        }
        return io.legado.engine.webview.BrowserDialogHelper.openAwait(
            activity, url, title, html, null,
            cookieSyncUrl = sourceUrl,
            jsInterface = this, jsInterfaceName = "java"
        )
    }
    fun importScript(path: String): String {
        return try { HttpClient.get(path).body } catch (_: Exception) { "" }
    }

    // ==================== 存储 ====================

    fun put(key: String, value: String): String {
        CacheManager.put("v_${sourceUrl}_$key", value)
        try {
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_store", android.content.Context.MODE_PRIVATE)
            prefs?.edit()?.putString("v_${sourceUrl}_$key", value)?.apply()
        } catch (_: Exception) {}
        return value
    }

    fun get(key: String): String {
        CacheManager.get("v_${sourceUrl}_$key")?.let { return it }
        return try {
            val prefs = activityRef.get()?.getSharedPreferences("legado_login_store", android.content.Context.MODE_PRIVATE)
            prefs?.getString("v_${sourceUrl}_$key", "") ?: ""
        } catch (_: Exception) { "" }
    }

    // ==================== 辅助 ====================

    private fun parseHeaders(headers: Any?): Map<String, String> {
        return when (headers) {
            is Map<*, *> -> headers.entries.filter { it.key is String && it.value is String }.associate { it.key as String to it.value as String }
            is NativeObject -> headers.toAnyMap().orEmpty().mapValues { it.value?.toString().orEmpty() }
            is String -> UrlOptionParser.parseHeaders(headers)
            else -> emptyMap()
        }
    }

    private fun Any?.toAnyMap(): Map<String, Any?>? {
        return when (this) {
            null, is Undefined -> null
            is Map<*, *> -> entries.associate { (key, value) -> key.toString() to value }
            is NativeObject -> ids.associate { id ->
                val key = id.toString()
                val value = when (id) {
                    is Number -> get(id.toInt(), this)
                    else -> get(key, this)
                }
                key to value.takeUnless { it is Undefined }
            }
            else -> null
        }
    }

    private fun decodeDataText(url: String): String {
        val payload = url.substringAfter(",", "")
        if (payload.isBlank()) return ""
        return if (url.substringBefore(",", "").contains(";base64", true)) {
            String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT), Charsets.UTF_8)
        } else {
            java.net.URLDecoder.decode(payload, "UTF-8")
        }
    }

    private fun fallbackBrowserResponse(url: String, html: String?): StrResponse {
        return try {
            val body = when {
                !html.isNullOrBlank() -> html
                url.startsWith("data:text/html", true) -> decodeDataText(url)
                url.startsWith("http://") || url.startsWith("https://") -> HttpClient.get(url).body
                else -> ""
            }
            StrResponse(HttpResponse(url, body, 200))
        } catch (_: Exception) {
            StrResponse(HttpResponse(url, "", 500))
        }
    }

    private fun openBrowserDialog(
        url: String,
        title: String,
        html: String?,
        preloadJs: String?,
        waitForResult: Boolean
    ): StrResponse {
        val activity = activityRef.get() ?: return fallbackBrowserResponse(url, html)
        val result = AtomicReference(initialBrowserResponse(url, html))
        val completed = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        fun complete(response: StrResponse) {
            if (completed.compareAndSet(false, true)) {
                result.set(response)
                latch.countDown()
            }
        }

        Handler(Looper.getMainLooper()).post {
            val dialog = Dialog(activity)
            val webView = WebView(activity)
            val currentUrl = AtomicReference(url)

            fun syncCookies(targetUrl: String?) {
                val cleanUrl = targetUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: return
                val cookie = CookieManager.getInstance().getCookie(cleanUrl) ?: return
                if (cookie.isNotBlank()) {
                    CookieStore.replaceCookie(cleanUrl, cookie)
                    CookieManager.getInstance().flush()
                }
            }

            fun finishWithWebContent(code: Int) {
                val finalUrl = currentUrl.get().ifBlank { url }
                syncCookies(finalUrl)
                webView.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()") { value ->
                    val body = decodeJsString(value)
                    runCatching { dialog.dismiss() }
                    complete(StrResponse(HttpResponse(finalUrl, body, code)))
                }
            }

            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            val toolbar = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(12, 8, 12, 8)
            }
            toolbar.addView(TextView(activity).apply {
                text = title.ifBlank { url }
                textSize = 16f
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            toolbar.addView(Button(activity).apply {
                text = "完成"
                setOnClickListener { finishWithWebContent(200) }
            })
            toolbar.addView(Button(activity).apply {
                text = "关闭"
                setOnClickListener {
                    runCatching { dialog.dismiss() }
                    complete(result.get())
                }
            })
            root.addView(toolbar)
            root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = io.legado.engine.constant.AppConst.USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            // 把插件 CookieStore 中已有的 cookie 注入 WebView
            val preSyncUrl = url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: sourceUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            preSyncUrl?.let { injectStoredCookies(it) }
            webView.addJavascriptInterface(this@LoginJsBridge, "java")
            webView.webChromeClient = WebChromeClient()
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                    super.onPageFinished(view, pageUrl)
                    if (!pageUrl.isNullOrBlank()) currentUrl.set(pageUrl)
                    syncCookies(pageUrl)
                    preloadJs?.takeIf { it.isNotBlank() }?.let { view?.evaluateJavascript(it, null) }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            dialog.setOnCancelListener { complete(result.get()) }
            dialog.setContentView(root)
            dialog.show()
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            when {
                !html.isNullOrBlank() -> webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null)
                url.startsWith("data:text/html", true) -> webView.loadDataWithBaseURL(sourceUrl, decodeDataText(url), "text/html", "UTF-8", null)
                url.startsWith("http://") || url.startsWith("https://") -> webView.loadUrl(url)
                else -> webView.loadDataWithBaseURL(sourceUrl, url, "text/html", "UTF-8", null)
            }
        }

        if (waitForResult) {
            try {
                if (!latch.await(10, TimeUnit.MINUTES)) {
                    complete(result.get())
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                complete(result.get())
            }
        }
        return result.get()
    }

    /**
     * 把插件 CookieStore 中某域名的 cookie 注入 Android WebView CookieManager，
     * 使 WebView loadUrl 时能自动携带已登录的 cookie。
     */
    private fun injectStoredCookies(targetUrl: String) {
        try {
            val stored = CookieStore.getCookieHeader(targetUrl)["Cookie"]
            if (!stored.isNullOrBlank()) {
                CookieManager.getInstance().setCookie(targetUrl, stored)
                CookieManager.getInstance().flush()
            }
        } catch (_: Exception) {}
    }

    private fun decodeJsString(value: String?): String {
        if (value.isNullOrBlank() || value == "null") return ""
        return try {
            com.google.gson.JsonParser.parseString(value).asString
        } catch (_: Exception) {
            value.trim('"')
        }
    }

    private fun initialBrowserResponse(url: String, html: String?): StrResponse {
        val body = when {
            !html.isNullOrBlank() -> html
            url.startsWith("data:text/html", true) -> decodeDataText(url)
            else -> ""
        }
        return StrResponse(HttpResponse(url, body, 200))
    }
}