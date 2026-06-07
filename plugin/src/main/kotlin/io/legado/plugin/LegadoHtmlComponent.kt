package io.legado.plugin

import android.content.Context
import android.graphics.Color
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    val baseUrl: String = ""
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
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 640.dp),
            factory = {
                WebView(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    loadHtml()
                }
            },
            update = { webView -> webView.loadHtml() }
        )
    }

    private fun WebView.loadHtml() {
        loadDataWithBaseURL(data.baseUrl.ifBlank { null }, data.html, "text/html", "UTF-8", null)
    }
}
