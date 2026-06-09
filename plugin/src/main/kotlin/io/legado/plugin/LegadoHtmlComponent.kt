package io.legado.plugin

import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.engine.http.CookieStore
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.dom4j.DocumentHelper
import org.dom4j.Element

@Serializable
data class LegadoHtmlComponentData(
    val html: String,
    val baseUrl: String = "",
    val sourceUrl: String = "",
    val sourceJson: String = "",
    val jsLib: String = ""
) : AbstractContentComponentData() {
    override val id: String = ID

    override fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)

    override fun toHtmlElement(context: Context): Element =
        DocumentHelper.createElement("div").apply {
            addCDATA(html)
        }

    companion object {
        const val ID = "legado_html"
        val jsonSerializer = object : ComponentDataJsonElementSerializer<LegadoHtmlComponentData> {
            override fun toJsonElement(data: LegadoHtmlComponentData): JsonElement =
                Json.encodeToJsonElement(data)

            override fun fromJsonElement(json: JsonElement): LegadoHtmlComponentData =
                Json.decodeFromJsonElement(json)
        }
    }
}

class LegadoHtmlComponent(
    data: LegadoHtmlComponentData,
    private val context: Context
) : AbstractContentComponent<LegadoHtmlComponentData>(data) {
    override val id: String = LegadoHtmlComponentData.ID

    @Composable
    override fun Content(modifier: Modifier) {
        val source = remember(data.sourceJson) { parseLegadoHtmlSource(data.sourceJson) }
        val sourceUrl = data.sourceUrl.ifBlank { source?.bookSourceUrl ?: data.baseUrl }
        val loginBridge = remember(sourceUrl, source) {
            LoginJsBridge(null, sourceUrl, bookSource = source)
        }
        val javaBridge = remember(loginBridge) { LegadoJavaWebBridge(loginBridge) }
        val sourceBridge = remember(loginBridge) { LegadoWebBridge(loginBridge) }
        val cacheBridge = remember { LegadoCacheWebBridge() }
        val html = remember(data.html, data.jsLib, sourceUrl, data.baseUrl) {
            injectLegadoWebBootstrap(data.html, data.jsLib, sourceUrl, data.baseUrl)
        }
        var webViewRef by remember { mutableStateOf<WebView?>(null) }
        DisposableEffect(Unit) {
            onDispose {
                webViewRef?.destroy()
                webViewRef = null
            }
        }
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 1200.dp),
            factory = {
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    injectStoredCookiesToWebView(data.baseUrl.ifBlank { sourceUrl })
                    addJavascriptInterface(javaBridge, "java")
                    addJavascriptInterface(sourceBridge, "source")
                    addJavascriptInterface(cacheBridge, "cache")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            syncCookie(url ?: data.baseUrl)
                        }

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                    }
                    webViewRef = this
                    loadHtml(html)
                }
            },
            update = { webView -> webView.loadHtml(html) }
        )
    }

    private fun WebView.loadHtml(html: String) {
        loadDataWithBaseURL(data.baseUrl.ifBlank { null }, html, "text/html", "UTF-8", null)
    }

    private fun syncCookie(url: String) {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return
        val cookie = CookieManager.getInstance().getCookie(url) ?: return
        CookieStore.replaceCookie(url, cookie)
    }
}
