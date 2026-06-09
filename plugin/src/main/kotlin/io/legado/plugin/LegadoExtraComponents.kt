package io.legado.plugin

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.script.ScriptBindings
import com.google.gson.Gson
import io.legado.engine.constant.isTrue
import io.legado.engine.data.Book
import io.legado.engine.data.BookChapter
import io.legado.engine.data.BookSource
import io.legado.engine.http.HttpClient
import io.legado.engine.rule.AnalyzeUrl
import io.legado.engine.rule.RuleAnalyzer
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponent
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentData
import io.nightfish.lightnovelreader.api.content.component.ComponentDataJsonElementSerializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.dom4j.DocumentHelper
import org.dom4j.Element
import java.net.URLDecoder
import java.util.Base64

@Serializable
data class LegadoImageComponentData(
    val uri: String,
    val style: String = "",
    val click: String = "",
    val width: String = "",
    val src: String = "",
    val headers: Map<String, String> = emptyMap(),
    val sourceJson: String = "",
    val bookJson: String = "",
    val chapterJson: String = ""
) : AbstractContentComponentData() {
    override val id: String = ID

    override fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)

    override fun toHtmlElement(context: Context): Element =
        DocumentHelper.createElement("div").apply {
            addElement("img").addAttribute("src", uri)
        }

    companion object {
        const val ID = "legado_image"
        val jsonSerializer = object : ComponentDataJsonElementSerializer<LegadoImageComponentData> {
            override fun toJsonElement(data: LegadoImageComponentData): JsonElement =
                Json.encodeToJsonElement(data)

            override fun fromJsonElement(json: JsonElement): LegadoImageComponentData =
                Json.decodeFromJsonElement(json)
        }
    }
}

class LegadoImageComponent(
    data: LegadoImageComponentData,
    private val context: Context
) : AbstractContentComponent<LegadoImageComponentData>(data) {
    override val id: String = LegadoImageComponentData.ID

    @Composable
    override fun Content(modifier: Modifier) {
        var bitmap by remember(data.uri, data.headers) { mutableStateOf<Bitmap?>(null) }
        var failed by remember(data.uri, data.headers) { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val style = data.style.trim().uppercase()
        val density = LocalDensity.current

        LaunchedEffect(data.uri, data.headers) {
            failed = false
            bitmap = withContext(Dispatchers.IO) {
                loadLegadoBitmap(context, data.uri, data.headers)
            }
            failed = bitmap == null
        }

        val alignment = when (style) {
            "LEFT" -> Alignment.CenterStart
            "RIGHT" -> Alignment.CenterEnd
            else -> Alignment.Center
        }
        val boxModifier = when (style) {
            "SINGLE" -> modifier.fillMaxWidth().heightIn(min = 420.dp, max = 1400.dp)
            "TEXT" -> modifier.fillMaxWidth().heightIn(min = 36.dp, max = 72.dp)
            else -> modifier.fillMaxWidth().heightIn(min = 96.dp, max = 1200.dp)
        }

        Box(
            modifier = boxModifier.padding(vertical = if (style == "TEXT") 2.dp else 6.dp),
            contentAlignment = alignment
        ) {
            val loaded = bitmap
            if (loaded != null) {
                val ratio = (loaded.width.toFloat() / loaded.height.coerceAtLeast(1)).coerceIn(0.1f, 12f)
                val widthModifier = imageWidthModifier(data.width, style, loaded.width, density.density)
                val imageModifier = when (style) {
                    "FULL", "SINGLE" -> widthModifier.fillMaxWidth().aspectRatio(ratio)
                    "LEFT", "RIGHT" -> widthModifier.aspectRatio(ratio)
                    "TEXT" -> widthModifier.sizeIn(maxWidth = 72.dp, maxHeight = 56.dp)
                    else -> widthModifier.aspectRatio(ratio)
                }.then(
                    if (data.click.isNotBlank()) {
                        Modifier.clickable {
                            scope.launch(Dispatchers.IO) {
                                runLegadoImageClick(data)
                            }
                        }
                    } else {
                        Modifier
                    }
                )
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit
                )
            } else if (failed) {
                Text(
                    text = data.uri,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    }
}

@Serializable
data class LegadoActionComponentData(
    val label: String,
    val event: String = "",
    val action: String = "",
    val sourceJson: String = "",
    val bookJson: String = "",
    val chapterJson: String = "",
    val title: String = "",
    val baseUrl: String = "",
    val result: String = ""
) : AbstractContentComponentData() {
    override val id: String = ID

    override fun toJsonElement(): JsonElement = Json.encodeToJsonElement(this)

    override fun toHtmlElement(context: Context): Element =
        DocumentHelper.createElement("button").apply {
            addText(label)
        }

    companion object {
        const val ID = "legado_action"
        val jsonSerializer = object : ComponentDataJsonElementSerializer<LegadoActionComponentData> {
            override fun toJsonElement(data: LegadoActionComponentData): JsonElement =
                Json.encodeToJsonElement(data)

            override fun fromJsonElement(json: JsonElement): LegadoActionComponentData =
                Json.decodeFromJsonElement(json)
        }
    }
}

class LegadoActionComponent(
    data: LegadoActionComponentData,
    private val context: Context
) : AbstractContentComponent<LegadoActionComponentData>(data) {
    override val id: String = LegadoActionComponentData.ID

    @Composable
    override fun Content(modifier: Modifier) {
        val scope = rememberCoroutineScope()
        var running by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("") }
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                enabled = !running,
                onClick = {
                    running = true
                    status = ""
                    scope.launch {
                        val outcome = withContext(Dispatchers.IO) { runLegadoAction(data) }
                        running = false
                        status = outcome.message
                        if (!outcome.openUrl.isNullOrBlank()) {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(outcome.openUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }.onFailure {
                                status = outcome.openUrl
                            }
                        }
                    }
                }
            ) {
                Text(if (running) "执行中" else data.label.ifBlank { "执行" })
            }
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class LegadoActionOutcome(
    val message: String = "",
    val openUrl: String? = null
)

private val extraGson = Gson()

private fun loadLegadoBitmap(context: Context, uri: String, headers: Map<String, String>): Bitmap? {
    if (uri.isBlank()) return null
    return runCatching {
        when {
            uri.startsWith("data:", true) -> decodeDataUriBytes(uri)
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            uri.startsWith("content://", true) -> context.contentResolver
                .openInputStream(Uri.parse(uri))
                ?.use(BitmapFactory::decodeStream)
            uri.startsWith("file://", true) -> BitmapFactory.decodeFile(Uri.parse(uri).path.orEmpty())
            uri.startsWith("http://", true) || uri.startsWith("https://", true) -> {
                val bytes = HttpClient.getByteArray(uri, headers = headers)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            else -> BitmapFactory.decodeFile(uri)
        }
    }.getOrNull()
}

private fun runLegadoImageClick(data: LegadoImageComponentData) {
    if (data.click.isBlank()) return
    val source = parseSource(data.sourceJson) ?: return
    val book = parseBook(data.bookJson)
    val chapter = parseChapter(data.chapterJson)
    runCatching {
        source.evalJS(data.click) { bindings ->
            bindings["java"] = LegadoActionJsBridge(source, book, chapter)
            bindings["book"] = book
            bindings["chapter"] = chapter
            bindings["result"] = data.src.ifBlank { data.uri }
            bindings["src"] = data.src.ifBlank { data.uri }
        }
    }
}

private fun runLegadoAction(data: LegadoActionComponentData): LegadoActionOutcome {
    val source = parseSource(data.sourceJson) ?: return LegadoActionOutcome("书源状态不可用")
    val book = parseBook(data.bookJson)
    val chapter = parseChapter(data.chapterJson)
    val scriptOrUrl = data.action.ifBlank { source.getContentRule().callBackJs.orEmpty() }
    if (scriptOrUrl.isBlank()) return LegadoActionOutcome("没有可执行脚本")
    val directUrl = scriptOrUrl.trim()
    if (!scriptOrUrl.isExplicitJs() && !scriptOrUrl.contains("{{") && (
            directUrl.startsWith("http://", true) || directUrl.startsWith("https://", true)
        )
    ) {
        return LegadoActionOutcome("已打开浏览器", directUrl)
    }
    if (!scriptOrUrl.isExplicitJs() && scriptOrUrl.contains("{{") && scriptOrUrl.contains("}}")) {
        val url = runCatching { resolveLegadoActionUrl(scriptOrUrl, source, book, chapter, data) }.getOrNull()
        if (!url.isNullOrBlank() && (url.startsWith("http://", true) || url.startsWith("https://", true))) {
            return LegadoActionOutcome("已打开浏览器", url)
        }
    }
    val value = runCatching {
        source.evalJS(unwrapLegadoJsBlock(scriptOrUrl)) { bindings ->
            putLegadoActionBindings(bindings, source, book, chapter, data)
        }
    }.getOrElse {
        return LegadoActionOutcome("执行失败: ${it.message ?: it.javaClass.simpleName}")
    }
    val text = value?.toString().orEmpty()
    return when {
        text.startsWith("http://", true) || text.startsWith("https://", true) ->
            LegadoActionOutcome("已打开浏览器", text)
        text.isTrue() ->
            LegadoActionOutcome("执行完成，请重新打开章节刷新内容")
        text.isNotBlank() ->
            LegadoActionOutcome(text)
        else ->
            LegadoActionOutcome("执行完成")
    }
}

private fun resolveLegadoActionUrl(
    raw: String,
    source: BookSource,
    book: Book?,
    chapter: BookChapter?,
    data: LegadoActionComponentData
): String {
    val analyzer = RuleAnalyzer(raw)
    val replaced = analyzer.innerRule("{{", "}}") { js ->
        val value = source.evalJS(js) { bindings ->
            putLegadoActionBindings(bindings, source, book, chapter, data)
        }
        when (value) {
            null -> ""
            is Double -> if (value % 1.0 == 0.0) "%.0f".format(value) else value.toString()
            else -> value.toString()
        }
    }
    return AnalyzeUrl.getAbsoluteURL(data.baseUrl.ifBlank { source.bookSourceUrl }, replaced.ifBlank { raw })
}

private fun putLegadoActionBindings(
    bindings: ScriptBindings,
    source: BookSource,
    book: Book?,
    chapter: BookChapter?,
    data: LegadoActionComponentData
) {
    bindings["java"] = LegadoActionJsBridge(source, book, chapter)
    bindings["event"] = data.event
    bindings["book"] = book
    bindings["chapter"] = chapter
    bindings["title"] = data.title
    bindings["baseUrl"] = data.baseUrl
    bindings["result"] = data.result.takeIf { it.isNotBlank() }
    bindings["src"] = null
}

private class LegadoActionJsBridge(
    private val source: BookSource,
    private val book: Book?,
    private val chapter: BookChapter?
) : LoginJsBridge(null, source.bookSourceUrl, bookSource = source) {
    override fun put(key: String, value: String): String {
        chapter?.putVariable(key, value)
        book?.putVariable(key, value)
        source.put(key, value)
        return value
    }

    override fun get(key: String): String {
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: book?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source.get(key).takeIf { it.isNotEmpty() }
            ?: ""
    }

    override fun putVariable(key: String, value: String): String = put(key, value)
    override fun getVariable(key: String): String = get(key)

    override fun putVariable(value: String?): String {
        val safeValue = value.orEmpty()
        chapter?.putVariable(safeValue)
        book?.putVariable(safeValue)
        source.putVariable(safeValue)
        return safeValue
    }

    override fun getVariable(): String {
        return chapter?.getVariableValue()?.takeIf { it.isNotEmpty() }
            ?: book?.getVariableValue()?.takeIf { it.isNotEmpty() }
            ?: source.getVariable()
    }
}

private fun imageWidthModifier(width: String, style: String, intrinsicPx: Int, density: Float): Modifier {
    val normalized = width.trim()
    if (style == "FULL" || style == "SINGLE") return Modifier.fillMaxWidth()
    if (normalized.endsWith("%")) {
        val fraction = normalized.dropLast(1).toFloatOrNull()
            ?.div(100f)
            ?.coerceIn(0.05f, 1f)
        if (fraction != null) return Modifier.fillMaxWidth(fraction)
    }
    val px = normalized.toFloatOrNull()
    if (px != null && px > 0f) {
        return Modifier.sizeIn(maxWidth = (px / density.coerceAtLeast(0.1f)).dp)
    }
    val defaultFraction = when (style) {
        "LEFT", "RIGHT" -> 0.86f
        "TEXT" -> null
        else -> 0.92f
    }
    return defaultFraction?.let { Modifier.fillMaxWidth(it) }
        ?: Modifier.sizeIn(maxWidth = intrinsicPx.coerceAtLeast(1).dp)
}

private fun String.isExplicitJs(): Boolean =
    trimStart().let { it.startsWith("@js:", true) || it.startsWith("<js>", true) }

private fun parseSource(json: String): BookSource? =
    runCatching { extraGson.fromJson(json, BookSource::class.java) }.getOrNull()

private fun parseBook(json: String): Book? =
    runCatching { extraGson.fromJson(json, Book::class.java) }.getOrNull()

private fun parseChapter(json: String): BookChapter? =
    runCatching { extraGson.fromJson(json, BookChapter::class.java) }.getOrNull()

private fun decodeDataUriBytes(uri: String): ByteArray? {
    val comma = uri.indexOf(',')
    if (comma < 0) return null
    val meta = uri.substring(0, comma)
    val payload = uri.substring(comma + 1)
    return if (meta.contains(";base64", ignoreCase = true)) {
        runCatching { Base64.getDecoder().decode(payload) }
            .getOrElse { runCatching { Base64.getMimeDecoder().decode(payload) }.getOrNull() }
    } else {
        runCatching { URLDecoder.decode(payload, "UTF-8").toByteArray() }.getOrNull()
    }
}

private fun unwrapLegadoJsBlock(js: String): String {
    val trimmed = js.trim()
    return when {
        trimmed.startsWith("@js:", true) -> trimmed.substring(4)
        trimmed.startsWith("<js>", true) -> {
            val end = trimmed.lastIndexOf("<").takeIf { it > 4 } ?: trimmed.length
            trimmed.substring(4, end)
        }
        else -> js
    }
}
