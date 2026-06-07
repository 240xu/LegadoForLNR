package io.legado.engine.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.engine.constant.AppConst
import io.legado.engine.http.HttpResponse
import io.legado.engine.shim.AndroidContext
import io.legado.engine.shim.Debug
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BackstageWebView {
    fun getSource(
        html: String?,
        url: String?,
        js: String?,
        headerMap: Map<String, String>?,
        sourceRegex: String? = null,
        overrideUrlRegex: String? = null,
        delayTime: Long = 0,
        timeout: Long = 30000
    ): HttpResponse {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw IllegalStateException("BackstageWebView must run from a background thread")
        }

        val latch = CountDownLatch(1)
        val holder = arrayOfNulls<String>(1)
        val finalUrl = arrayOf(url.orEmpty())
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            createWebView(
                html = html,
                url = url,
                js = js,
                headerMap = headerMap.orEmpty(),
                sourceRegex = sourceRegex,
                overrideUrlRegex = overrideUrlRegex,
                delayTime = delayTime,
                holder = holder,
                finalUrl = finalUrl,
                latch = latch
            )
        }

        val ok = latch.await(timeout.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
        if (!ok) {
            Debug.log("BackstageWebView timeout: ${url.orEmpty()}")
        }
        return HttpResponse(finalUrl[0], holder[0].orEmpty(), if (ok) 200 else 504)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(
        html: String?,
        url: String?,
        js: String?,
        headerMap: Map<String, String>,
        sourceRegex: String?,
        overrideUrlRegex: String?,
        delayTime: Long,
        holder: Array<String?>,
        finalUrl: Array<String>,
        latch: CountDownLatch
    ) {
        val webView = WebView(AndroidContext.appCtx)
        var finished = false

        fun finish(value: String?) {
            if (finished) return
            finished = true
            holder[0] = value.orEmpty()
            runCatching {
                webView.stopLoading()
                webView.destroy()
            }
            latch.countDown()
        }

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = headerMap["User-Agent"]
                ?: headerMap["user-agent"]
                ?: AppConst.USER_AGENT
        }
        webView.addJavascriptInterface(ResultBridge(::finish), "legadoBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val nextUrl = request?.url?.toString().orEmpty()
                if (nextUrl.isNotBlank()) {
                    finalUrl[0] = nextUrl
                    if (!overrideUrlRegex.isNullOrBlank() && Regex(overrideUrlRegex).containsMatchIn(nextUrl)) {
                        finish(nextUrl)
                        return true
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                if (!pageUrl.isNullOrBlank()) finalUrl[0] = pageUrl
                val wait = delayTime.coerceAtLeast(0)
                view?.postDelayed({ evaluate(view, js, sourceRegex, ::finish) }, wait)
            }
        }

        if (!html.isNullOrBlank()) {
            webView.loadDataWithBaseURL(url ?: "about:blank", html, "text/html", "utf-8", null)
        } else if (!url.isNullOrBlank()) {
            webView.loadUrl(url, headerMap)
        } else {
            finish("")
        }
    }

    private fun evaluate(
        webView: WebView?,
        js: String?,
        sourceRegex: String?,
        finish: (String?) -> Unit
    ) {
        val script = when {
            !js.isNullOrBlank() -> js
            !sourceRegex.isNullOrBlank() -> "(function(){return document.documentElement.outerHTML;})()"
            else -> "(function(){return document.documentElement.outerHTML;})()"
        }
        webView?.evaluateJavascript(script) { raw ->
            val value = decodeJsString(raw)
            if (!sourceRegex.isNullOrBlank()) {
                val match = Regex(sourceRegex).find(value)
                finish(match?.groups?.get(1)?.value ?: match?.value ?: "")
            } else {
                finish(value)
            }
        } ?: finish("")
    }

    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null") return ""
        if (raw.length < 2 || raw.first() != '"' || raw.last() != '"') return raw
        val body = raw.substring(1, raw.length - 1)
        return buildString(body.length) {
            var i = 0
            while (i < body.length) {
                val ch = body[i]
                if (ch == '\\' && i + 1 < body.length) {
                    when (val next = body[++i]) {
                        '"', '\\', '/' -> append(next)
                        'b' -> append('\b')
                        'f' -> append('\u000C')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            val hex = body.substring(i + 1, (i + 5).coerceAtMost(body.length))
                            if (hex.length == 4) {
                                append(hex.toIntOrNull(16)?.toChar() ?: "\\u$hex")
                                i += 4
                            } else append("\\u")
                        }
                        else -> append(next)
                    }
                } else {
                    append(ch)
                }
                i++
            }
        }
    }

    private class ResultBridge(private val finish: (String?) -> Unit) {
        @JavascriptInterface
        fun result(value: String?) {
            finish(value)
        }
    }
}
