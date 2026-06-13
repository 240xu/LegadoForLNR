package io.legado.plugin

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.legado.engine.data.BookSource
import io.legado.engine.http.CookieStore
import io.legado.engine.model.RowUi
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.AndroidContext
import io.legado.engine.shim.CacheManager
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ScriptableObject

private val managerGson = Gson()

@Composable
fun LegadoSourceManagerContent(
    hostContext: Context,
    paddingValues: PaddingValues,
    activeDataSourceId: Int? = null
) {
    val composeContext = LocalContext.current
    val appContext = remember(hostContext, composeContext) {
        (composeContext.applicationContext ?: hostContext.applicationContext ?: hostContext)
    }
    val dataSource = remember(appContext) { LegadoJsonWebDataSource(appContext) }
    val isActiveDataSource = activeDataSourceId == null || activeDataSourceId == dataSource.id
    var sources by remember { mutableStateOf(emptyList<BookSource>()) }
    var statusText by remember { mutableStateOf("") }
    var showPasteDialog by remember { mutableStateOf(false) }
    var loginSource by remember { mutableStateOf<BookSource?>(null) }

    fun refreshSources() {
        dataSource.reloadSources()
        sources = dataSource.getAllSources()
    }

    fun importJson(json: String) {
        val count = dataSource.importSources(json)
        refreshSources()
        statusText = if (count > 0) {
            "已导入 $count 个书源"
        } else {
            "没有识别到有效 Legado JSON 书源"
        }
    }

    LaunchedEffect(Unit) {
        if (!AndroidContext.isInitialized()) AndroidContext.init(appContext)
        refreshSources()
    }

    Column(
        modifier = Modifier
            .padding(paddingValues)
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Legado JSON 书源",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "导入、启停、删除书源；loginUi 登录可直接在此页处理。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = if (isActiveDataSource) {
                "宿主当前数据源已是 Legado JSON"
            } else {
                "宿主当前数据源还不是 Legado JSON；导入后请到 LNR 数据源切换页应用 Legado JSON"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isActiveDataSource) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString()
                    if (text.isNullOrBlank()) {
                        statusText = "剪贴板为空"
                    } else {
                        importJson(text)
                    }
                }
            ) {
                Text("剪贴板导入")
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { showPasteDialog = true }
            ) {
                Text("粘贴导入")
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                runCatching { SourceManagerActivity.start(appContext) }
                    .onFailure {
                        statusText = "当前 .lnrp 导入模式不能直接启动插件 Activity，已保留旧入口等待宿主适配"
                    }
            }
        ) {
            Text("尝试打开旧版 Activity 管理器")
        }

        if (statusText.isNotBlank()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Divider()

        Text(
            text = "已导入 ${sources.size} 个书源，启用 ${sources.count { it.enabled }} 个",
            style = MaterialTheme.typography.bodyMedium
        )

        if (sources.isEmpty()) {
            Text(
                text = "还没有书源。可以复制 lyc486 版 Legado JSON 后用剪贴板或粘贴导入。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            sources.sortedWith(compareBy<BookSource> { !it.enabled }.thenBy { it.bookSourceName })
                .forEach { source ->
                    SourceCard(
                        source = source,
                        loggedIn = dataSource.isLoggedIn(source),
                        onEnabledChange = { enabled ->
                            dataSource.setSourceEnabled(source.bookSourceUrl, enabled)
                            refreshSources()
                        },
                        onLogin = {
                            loginSource = source
                        },
                        onLegacyLogin = {
                            if (!dataSource.startLogin(source)) {
                                statusText = "旧版 Activity 登录入口不可用，已切换到内嵌登录"
                                loginSource = source
                            }
                        },
                        onLogout = {
                            dataSource.logout(source)
                            source.removeLoginInfo()
                            source.removeLoginHeader()
                            statusText = "已清理 ${source.bookSourceName.ifBlank { source.bookSourceUrl }} 的登录状态"
                            refreshSources()
                        },
                        onClearCache = {
                            dataSource.clearSourceRuntimeCache(source)
                            statusText = "已清理 ${source.bookSourceName.ifBlank { source.bookSourceUrl }} 的运行缓存"
                            refreshSources()
                        },
                        onDelete = {
                            dataSource.deleteSource(source.bookSourceUrl)
                            refreshSources()
                        }
                    )
                }
        }
    }

    if (showPasteDialog) {
        PasteImportDialog(
            onDismiss = { showPasteDialog = false },
            onImport = {
                showPasteDialog = false
                importJson(it)
            }
        )
    }

    loginSource?.let { source ->
        LegadoLoginDialog(
            source = source,
            onDismiss = {
                loginSource = null
                refreshSources()
            },
            onStatus = {
                statusText = it
            }
        )
    }
}

@Composable
private fun SourceCard(
    source: BookSource,
    loggedIn: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onLogin: () -> Unit,
    onLegacyLogin: () -> Unit,
    onLogout: () -> Unit,
    onClearCache: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = source.bookSourceName.ifBlank { source.bookSourceUrl },
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = source.bookSourceUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            Text(
                text = if (loggedIn) "已有登录状态" else "未检测到登录状态",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !source.loginUi.isNullOrBlank() || !source.loginUrl.isNullOrBlank(),
                    onClick = onLogin
                ) {
                    Text("登录")
                }
                OutlinedButton(
                    enabled = !source.loginUi.isNullOrBlank() || !source.loginUrl.isNullOrBlank(),
                    onClick = onLegacyLogin
                ) {
                    Text("旧入口")
                }
                OutlinedButton(onClick = onLogout) {
                    Text("退出")
                }
                OutlinedButton(onClick = onClearCache) {
                    Text("清缓存")
                }
                TextButton(onClick = onDelete) {
                    Text("删除")
                }
            }
        }
    }
}

@Composable
private fun PasteImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 Legado JSON") },
        text = {
            OutlinedTextField(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp),
                value = text,
                onValueChange = { text = it },
                minLines = 8,
                label = { Text("JSON") }
            )
        },
        confirmButton = {
            TextButton(onClick = { onImport(text) }) {
                Text("导入")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun LegadoLoginDialog(
    source: BookSource,
    onDismiss: () -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val resolvedLoginUi = remember(source) { resolveLoginUiJson(source) }
    val rows = remember(source, resolvedLoginUi) { parseLoginRows(resolvedLoginUi) }
    var formData by remember(source) { mutableStateOf(source.getLoginInfoMap().toMutableMap()) }
    var message by remember { mutableStateOf("") }

    fun updateData(data: Map<String, Any?>?) {
        if (data == null) return
        formData = formData.toMutableMap().apply {
            data.forEach { (key, value) ->
                if (key.isNotBlank()) put(key, value?.toString().orEmpty())
            }
        }
    }

    fun runAction(script: String?, defaultLogin: Boolean = false) {
        val trimmed = script?.trim().orEmpty()
        // Legado: 按钮 action 为 URL 时打开浏览器（如注册页面）
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(trimmed))
                activity?.startActivity(intent)
            } catch (_: Exception) {}
            return
        }
        val snapshot = formData.toMap()
        message = "正在执行登录脚本..."
        executeLoginJs(
            activity = activity,
            source = source,
            formData = snapshot,
            actionScript = script,
            runDefaultLogin = defaultLogin,
            onUpdateData = ::updateData,
            onRebuild = {},
            onStatus = {
                message = it
                onStatus(it)
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("登录 - ${source.bookSourceName.ifBlank { source.bookSourceUrl }}") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (rows.isNotEmpty()) {
                    rows.forEach { row ->
                        LoginRow(
                            row = row,
                            value = formData[row.name] ?: row.default.orEmpty(),
                            onValueChange = { value ->
                                formData = formData.toMutableMap().apply { put(row.name, value) }
                            },
                            onAction = { runAction(row.action, defaultLogin = false) }
                        )
                    }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            source.putLoginInfo(managerGson.toJson(formData))
                            runAction(null, defaultLogin = true)
                        }
                    ) {
                        Text("保存并执行 login()")
                    }
                } else if (!source.loginUrl.isNullOrBlank()) {
                    Text(
                        text = "此书源使用 WebView 登录，完成网页登录后关闭窗口即可。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LegadoWebLoginPanel(
                        source = source,
                        activity = activity,
                        onStatus = {
                            message = it
                            onStatus(it)
                        }
                    )
                } else {
                    Text("此书源未配置 loginUi 或 loginUrl。")
                }

                if (message.isNotBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                source.putLoginInfo(managerGson.toJson(formData))
                onStatus("登录信息已保存")
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginRow(
    row: RowUi,
    value: String,
    onValueChange: (String) -> Unit,
    onAction: () -> Unit
) {
    when (row.type) {
        RowUi.Type.password, RowUi.Type.text -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = { newValue ->
                    onValueChange(newValue)
                    // Legado 20260131: text/password 类型支持 action 键，
                    // 用户完成输入后自动执行对应 JS
                    if (!row.action.isNullOrBlank()) {
                        onAction()
                    }
                },
                label = { Text(row.name) },
                visualTransformation = if (row.type == RowUi.Type.password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                }
            )
        }
        RowUi.Type.toggle -> {
            val onValue = row.chars?.getOrNull(1) ?: "true"
            val offValue = row.chars?.getOrNull(0) ?: "false"
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    modifier = Modifier.weight(1f),
                    text = row.name,
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = value == onValue,
                    onCheckedChange = { onValueChange(if (it) onValue else offValue) }
                )
            }
        }
        RowUi.Type.select -> {
            var expanded by remember { mutableStateOf(false) }
            val options = row.chars?.filterNotNull()?.filter { it.isNotBlank() }.orEmpty()
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                    readOnly = true,
                    value = value.ifBlank { options.firstOrNull().orEmpty() },
                    onValueChange = {},
                    label = { Text(row.name) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                expanded = false
                                onValueChange(option)
                            }
                        )
                    }
                }
            }
        }
        RowUi.Type.button -> {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onAction
            ) {
                Text(resolveButtonName(row))
            }
        }
        else -> {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = onValueChange,
                label = { Text(row.name.ifBlank { row.type }) }
            )
        }
    }
}

@Composable
private fun LegadoWebLoginPanel(
    source: BookSource,
    activity: Activity?,
    onStatus: (String) -> Unit
) {
    val loginUrl = remember(source) { resolveLoginUrl(source) }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(430.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = io.legado.engine.constant.AppConst.USER_AGENT
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                addJavascriptInterface(LoginJsBridge(activity, source.bookSourceUrl, bookSource = source), "java")
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        syncWebViewCookie(url ?: loginUrl)
                        onStatus("页面已加载，Cookie 已同步")
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                }
                if (loginUrl.startsWith("http://") || loginUrl.startsWith("https://")) {
                    loadUrl(loginUrl)
                } else {
                    loadDataWithBaseURL(source.bookSourceUrl, loginUrl, "text/html", "UTF-8", null)
                }
            }
        }
    )
}

private fun executeLoginJs(
    activity: Activity?,
    source: BookSource,
    formData: Map<String, String>,
    actionScript: String?,
    runDefaultLogin: Boolean,
    onUpdateData: (Map<String, Any?>?) -> Unit,
    onRebuild: () -> Unit,
    onStatus: (String) -> Unit
) {
    Thread {
        try {
            source.putLoginInfo(managerGson.toJson(formData))
            val bridge = LoginJsBridge(
                activity = activity,
                sourceUrl = source.bookSourceUrl,
                callback = object : LoginJsBridge.Callback {
                    override fun upLoginData(data: Map<String, Any?>?) {
                        onUpdateData(data)
                    }

                    override fun reLoginView(deltaUp: Boolean) {
                        onRebuild()
                    }
                },
                bookSource = source
            )
            bridge.loginData = formData.toMutableMap()
            val loginJs = source.getLoginJs().orEmpty()
            val cx = RhinoContext.enter()
            try {
                cx.optimizationLevel = -1
                val scope = cx.initStandardObjects()
                ScriptableObject.putProperty(scope, "java", RhinoContext.javaToJS(bridge, scope))
                ScriptableObject.putProperty(scope, "source", RhinoContext.javaToJS(bridge, scope))
                ScriptableObject.putProperty(scope, "cookie", RhinoContext.javaToJS(CookieStore, scope))
                ScriptableObject.putProperty(scope, "cache", RhinoContext.javaToJS(CacheManager, scope))
                val resultObj = cx.newObject(scope)
                formData.forEach { (key, value) -> ScriptableObject.putProperty(resultObj, key, value) }
                ScriptableObject.putProperty(scope, "result", resultObj)
                cx.evaluateString(scope, "result.get=function(key){return result[key] || '';};", "result_get", 1, null)
                if (loginJs.isNotBlank()) {
                    cx.evaluateString(scope, loginJs, "login_js", 1, null)
                }
                when {
                    !actionScript.isNullOrBlank() -> cx.evaluateString(scope, actionScript, "login_action", 1, null)
                    runDefaultLogin -> cx.evaluateString(scope, "if(typeof login==='function'){login();}", "login_default", 1, null)
                }
                // 登录脚本执行后，将 bridge 中的 cookie/header 回灌到 source
                val finalLoginHeader = bridge.getLoginHeader()
                if (!finalLoginHeader.isNullOrBlank()) {
                    source.putLoginHeader(finalLoginHeader)
                }
                mainThread { onStatus("登录脚本执行完成") }
            } finally {
                RhinoContext.exit()
            }
        } catch (e: Exception) {
            mainThread { onStatus("登录脚本执行失败: ${e.message ?: e.javaClass.simpleName}") }
        }
    }.start()
}

private fun resolveLoginUiJson(source: BookSource): String {
    val raw = source.loginUi?.takeIf { it.isNotBlank() } ?: return ""
    return try {
        val evaluated = when {
            raw.startsWith("@js:", true) -> source.evalJS("${source.getLoginJs() ?: ""}\n${raw.substring(4)}")
            raw.startsWith("<js>", true) -> {
                val end = raw.lastIndexOf("<").takeIf { it > 4 } ?: raw.length
                source.evalJS("${source.getLoginJs() ?: ""}\n${raw.substring(4, end)}")
            }
            else -> return raw
        }
        when (evaluated) {
            null -> raw
            is CharSequence -> evaluated.toString()
            is Map<*, *>, is Iterable<*>, is Array<*> -> managerGson.toJson(evaluated)
            else -> evaluated.toString()
        }
    } catch (_: Exception) {
        raw
    }
}

private fun parseLoginRows(loginUi: String): List<RowUi> {
    if (loginUi.isBlank()) return emptyList()
    return try {
        val type = object : TypeToken<List<RowUi>>() {}.type
        managerGson.fromJson<List<RowUi>>(loginUi, type) ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }
}

private fun resolveLoginUrl(source: BookSource): String {
    val raw = source.loginUrl.orEmpty()
    return try {
        when {
            raw.startsWith("@js:", true) -> source.evalJS(raw.substring(4))?.toString() ?: raw
            raw.startsWith("<js>", true) -> {
                val end = raw.lastIndexOf("<").takeIf { it > 4 } ?: raw.length
                source.evalJS(raw.substring(4, end))?.toString() ?: raw
            }
            else -> raw
        }
    } catch (_: Exception) {
        raw
    }
}

private fun resolveButtonName(row: RowUi): String {
    val raw = row.viewName ?: row.name
    return raw
        .takeIf { it.length in 2..80 && it.first() == '\'' && it.last() == '\'' }
        ?.substring(1, raw.length - 1)
        ?: raw.ifBlank { "执行" }
}

private fun syncWebViewCookie(url: String) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return
    val cookie = CookieManager.getInstance().getCookie(url) ?: return
    if (cookie.isNotBlank()) {
        CookieStore.replaceCookie(url, cookie)
        CookieManager.getInstance().flush()
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun mainThread(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}

@Suppress("unused")
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("legado", text))
}

@Suppress("unused")
private fun openExternalUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UrlOptionParser.strip(url))))
    }
}