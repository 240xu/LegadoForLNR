package io.legado.engine.webview

import android.app.Dialog
import android.content.Context
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import io.legado.engine.constant.AppConst
import io.legado.engine.http.CookieStore
import io.legado.engine.http.HttpClient
import io.legado.engine.http.HttpResponse
import io.legado.engine.http.StrResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 共享的浏览器Dialog工具，供JsExtensions和LoginJsBridge共用
 */
object BrowserDialogHelper {

    fun interface BrowserCallback {
        fun onResult(response: StrResponse)
    }

    /**
     * 打开浏览器Dialog，同步等待结果
     */
    fun openAwait(
        context: Context,
        url: String,
        title: String,
        html: String? = null,
        preloadJs: String? = null,
        cookieSyncUrl: String? = null,
        jsInterface: Any? = null,
        jsInterfaceName: String = "java"
    ): StrResponse {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // 主线程不能阻塞，回退到HTTP获取
            return fallbackResponse(url, html)
        }
        val result = AtomicReference(fallbackResponse(url, html))
        val latch = CountDownLatch(1)

        Handler(Looper.getMainLooper()).post {
            try {
                val dialog = Dialog(context)
                val webView = WebView(context)

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                val toolbar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 8, 12, 8)
                }
                toolbar.addView(TextView(context).apply {
                    text = title.ifBlank { url }
                    textSize = 16f
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                toolbar.addView(Button(context).apply {
                    text = "完成"
                    setOnClickListener {
                        webView.evaluateJavascript("(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()") { value ->
                            val body = decodeJsString(value)
                            syncCookies(webView, cookieSyncUrl ?: url)
                            result.set(StrResponse(HttpResponse(url, body, 200)))
                            runCatching { dialog.dismiss() }
                            latch.countDown()
                        }
                    }
                })
                toolbar.addView(Button(context).apply {
                    text = "关闭"
                    setOnClickListener {
                        runCatching { dialog.dismiss() }
                        latch.countDown()
                    }
                })
                root.addView(toolbar)
                root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = AppConst.USER_AGENT
                webView.settings.setSupportZoom(true)
                webView.settings.builtInZoomControls = true
                webView.settings.displayZoomControls = false
                webView.settings.loadWithOverviewMode = true
                webView.settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                // 注入已有Cookie
                (cookieSyncUrl ?: url).takeIf { it.startsWith("http") }?.let { injectStoredCookies(it) }

                // 注入JS接口
                if (jsInterface != null) {
                    webView.addJavascriptInterface(jsInterface, jsInterfaceName)
                }

                if (!preloadJs.isNullOrBlank()) {
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(preloadJs, null)
                        }
                    }
                }

                dialog.setContentView(root)
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                dialog.setOnCancelListener { latch.countDown() }
                dialog.show()

                if (!html.isNullOrBlank()) {
                    webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
                } else {
                    webView.loadUrl(url)
                }
            } catch (e: Exception) {
                latch.countDown()
            }
        }

        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        return result.get() ?: fallbackResponse(url, null)
    }

    /**
     * 非阻塞版本 - 仅显示浏览器
     */
    fun open(
        context: Context,
        url: String,
        title: String,
        html: String? = null,
        preloadJs: String? = null,
        jsInterface: Any? = null,
        jsInterfaceName: String = "java"
    ) {
        Handler(Looper.getMainLooper()).post {
            try {
                val dialog = Dialog(context)
                val webView = WebView(context)

                val root = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                val toolbar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(12, 8, 12, 8)
                }
                toolbar.addView(TextView(context).apply {
                    text = title.ifBlank { url }
                    textSize = 16f
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                toolbar.addView(Button(context).apply {
                    text = "关闭"
                    setOnClickListener { runCatching { dialog.dismiss() } }
                })
                root.addView(toolbar)
                root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = AppConst.USER_AGENT
                webView.settings.setSupportZoom(true)
                webView.settings.builtInZoomControls = true
                webView.settings.displayZoomControls = false
                webView.settings.loadWithOverviewMode = true
                webView.settings.useWideViewPort = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

                url.takeIf { it.startsWith("http") }?.let { injectStoredCookies(it) }

                if (jsInterface != null) {
                    webView.addJavascriptInterface(jsInterface, jsInterfaceName)
                }

                if (!preloadJs.isNullOrBlank()) {
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            view?.evaluateJavascript(preloadJs, null)
                        }
                    }
                }

                dialog.setContentView(root)
                dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                dialog.show()

                if (!html.isNullOrBlank()) {
                    webView.loadDataWithBaseURL(url, html, "text/html", "UTF-8", url)
                } else {
                    webView.loadUrl(url)
                }
            } catch (_: Exception) {}
        }
    }

    private fun syncCookies(webView: WebView, url: String?) {
        val cleanUrl = url?.takeIf { it.startsWith("http") } ?: return
        val cookie = CookieManager.getInstance().getCookie(cleanUrl) ?: return
        if (cookie.isNotBlank()) {
            CookieStore.replaceCookie(cleanUrl, cookie)
            CookieManager.getInstance().flush()
        }
    }

    private fun injectStoredCookies(url: String) {
        try {
            val cookies = CookieStore.getCookie(java.net.URL(url).host)
            if (cookies.isNotBlank()) {
                cookies.split(";").forEach { cookie ->
                    CookieManager.getInstance().setCookie(url, cookie.trim())
                }
                CookieManager.getInstance().flush()
            }
        } catch (_: Exception) {}
    }

    private fun fallbackResponse(url: String, html: String?): StrResponse {
        return try {
            val body = when {
                !html.isNullOrBlank() -> html
                url.startsWith("http") -> HttpClient.get(url).body
                else -> ""
            }
            StrResponse(HttpResponse(url, body, 200))
        } catch (e: Exception) {
            StrResponse(HttpResponse(url, "", 500))
        }
    }

    private fun decodeJsString(value: String?): String {
        if (value.isNullOrBlank() || value == "null") return ""
        val v = value.trim()
        if (v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length - 1)
                .replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
        }
        return v
    }
}
