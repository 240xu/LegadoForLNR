package io.legado.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import io.legado.engine.http.CookieStore
import io.legado.engine.http.HttpClient
import io.legado.engine.data.BookSource
import io.legado.engine.shim.CacheManager
import io.legado.engine.model.RowUi
import io.legado.engine.rule.AnalyzeRule
import com.google.gson.Gson
import io.legado.plugin.R
import com.google.gson.reflect.TypeToken
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ScriptableObject

/**
 * Legado 书源登录 Activity
 *
 * 支持两种登录模式：
 * 1. loginUi 模式：解析 loginUi JSON，动态构建表单
 *    - text/password: 文本输入，支持 action 键，支持保存勾选
 *    - button: 按钮，支持 viewName JS 表达式、action URL/JS
 *    - toggle: 开关
 *    - select: 下拉选择
 * 2. WebView 模式：当无 loginUi 时，直接用 WebView 加载 loginUrl
 *
 * 支持：
 * - @js: / <js> 前缀的 loginUi 动态生成
 * - loginCheckJs 登录状态检查
 * - 登录信息持久化
 * - JS 桥接：java.ajax/cookie/toast/upLoginData/reLoginView 等
 * - viewName 动态按钮文本
 * - text input action 键（完成输入后执行JS）
 * - 保存勾选框（文本类输入需用户主动打勾保存）
 */
class LoginActivity : Activity(), LoginJsBridge.Callback {

    companion object {
        const val EXTRA_SOURCE_JSON = "source_json"
        private const val PREFS_NAME = "legado_login_info"
        private val gson = Gson()

        fun newIntent(context: Context, sourceJson: String): Intent {
            return Intent().apply {
                setClassName("io.legado.plugin", LoginActivity::class.java.name)
                putExtra(EXTRA_SOURCE_JSON, sourceJson)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

        fun start(context: Context, source: BookSource) {
            context.startActivity(newIntent(context, gson.toJson(source)))
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var formContainer: LinearLayout
    private lateinit var scrollForm: ScrollView
    private lateinit var webviewContainer: FrameLayout
    private lateinit var btnSave: Button

    private var source: BookSource? = null
    private var rowUis: List<RowUi>? = null
    private var webView: WebView? = null
    private var loginJsBridge: LoginJsBridge? = null
    private var hasFormChanges = false
    private var useWebViewMode = false

    // 表单视图索引：name -> View
    private val formViews = mutableMapOf<String, View>()
    // 保存勾选框索引：name -> CheckBox
    private val saveCheckboxes = mutableMapOf<String, CheckBox>()
    // 视图名称按钮索引：name -> Button (用于动态更新viewName)
    private val viewNameButtons = mutableMapOf<String, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        formContainer = findViewById(R.id.form_container)
        scrollForm = findViewById(R.id.scroll_form)
        webviewContainer = findViewById(R.id.webview_container)

        // 添加保存按钮
        btnSave = Button(this).apply {
            text = "保存登录信息"
            visibility = View.GONE
            setOnClickListener { saveAndFinish() }
        }

        btnBack.setOnClickListener { onBackPressed() }

        val json = intent.getStringExtra(EXTRA_SOURCE_JSON) ?: run { finish(); return }
        source = try { gson.fromJson(json, BookSource::class.java) } catch (_: Exception) { null }
            ?: run { finish(); return }

        val src = source!!
        tvTitle.text = "登录 - ${src.bookSourceName.ifBlank { src.bookSourceUrl }}"
        loginJsBridge = LoginJsBridge(this, src.bookSourceUrl, this, src)

        val loginUiStr = src.loginUi
        val loginUrl = src.loginUrl

        when {
            !loginUiStr.isNullOrBlank() -> initFormLogin(src, loginUiStr)
            !loginUrl.isNullOrBlank() -> initWebViewLogin(src, loginUrl)
            else -> { Toast.makeText(this, "该书源未配置登录", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    // =========================================================================
    // 模式一：loginUi 表单登录
    // =========================================================================

    private fun initFormLogin(source: BookSource, loginUiStr: String) {
        useWebViewMode = false
        scrollForm.visibility = View.VISIBLE
        webviewContainer.visibility = View.GONE

        val actualJson = resolveLoginUiJson(loginUiStr)

        val type = object : TypeToken<List<RowUi>>() {}.type
        rowUis = try { gson.fromJson(actualJson, type) } catch (_: Exception) { null }
        if (rowUis.isNullOrEmpty()) {
            Toast.makeText(this, "loginUi 解析失败", Toast.LENGTH_SHORT).show(); finish(); return
        }

        val savedInfo = getLoginInfo(source)
        val contentLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }

        for (rowUi in rowUis!!) {
            when (rowUi.type) {
                RowUi.Type.text, RowUi.Type.password -> contentLayout.addTextInput(rowUi, savedInfo)
                RowUi.Type.toggle -> contentLayout.addToggle(rowUi, savedInfo)
                RowUi.Type.select -> contentLayout.addSelect(rowUi, savedInfo)
                RowUi.Type.button -> contentLayout.addButton(rowUi)
            }
        }

        // 保存按钮
        contentLayout.addView(btnSave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        scrollForm.addView(contentLayout)
        btnSave.visibility = View.VISIBLE

        // 初始化 viewName 按钮文本
        updateViewNameButtons()
    }

    /**
     * 添加文本输入（支持 action 键和保存勾选）
     */
    private fun LinearLayout.addTextInput(rowUi: RowUi, savedInfo: Map<String, String>) {
        // 标签 + 保存勾选框行
        val labelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val label = TextView(context).apply {
            text = rowUi.name; textSize = 14f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        labelRow.addView(label)

        // 保存勾选框
        val checkBox = CheckBox(context).apply {
            text = "保存"; textSize = 12f; isChecked = false
            setOnCheckedChangeListener { _, _ -> hasFormChanges = true }
        }
        saveCheckboxes[rowUi.name] = checkBox
        labelRow.addView(checkBox)
        addView(labelRow)

        val editText = EditText(context).apply {
            hint = rowUi.default ?: ""
            inputType = if (rowUi.type == RowUi.Type.password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            val savedValue = savedInfo[rowUi.name] ?: rowUi.default ?: ""
            setText(savedValue)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundResource(android.R.drawable.edit_text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { hasFormChanges = true }
                override fun afterTextChanged(s: Editable?) {
                    // 执行 action 键（完成输入后执行JS）
                    if (!rowUi.action.isNullOrBlank()) {
                        executeInputAction(rowUi.name, rowUi.action!!, s?.toString() ?: "")
                    }
                }
            })
        }
        formViews[rowUi.name] = editText
        addView(editText)
    }

    /**
     * 添加开关
     */
    private fun LinearLayout.addToggle(rowUi: RowUi, savedInfo: Map<String, String>) {
        val defaultOn = rowUi.default == (rowUi.chars?.getOrNull(1) ?: "true")
        val switch = Switch(context).apply {
            text = rowUi.name
            isChecked = savedInfo[rowUi.name]?.let { it == (rowUi.chars?.getOrNull(1) ?: "true") } ?: defaultOn
            textSize = 14f
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnCheckedChangeListener { _, _ -> hasFormChanges = true }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        formViews[rowUi.name] = switch
        addView(switch)
    }

    /**
     * 添加下拉选择
     */
    private fun LinearLayout.addSelect(rowUi: RowUi, savedInfo: Map<String, String>) {
        val label = TextView(context).apply { text = rowUi.name; textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(4), dp(8), dp(4)) }
        addView(label)
        val options = rowUi.chars?.filterNotNull()?.toTypedArray() ?: arrayOf()
        val spinner = Spinner(context).apply {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, options)
            setAdapter(adapter)
            val defaultIdx = options.indexOfFirst { it == (savedInfo[rowUi.name] ?: rowUi.default) }
            if (defaultIdx >= 0) setSelection(defaultIdx)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) { hasFormChanges = true }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        formViews[rowUi.name] = spinner
        addView(spinner)
    }

    /**
     * 添加按钮（支持 viewName JS 表达式和 action）
     */
    private fun LinearLayout.addButton(rowUi: RowUi) {
        val button = Button(context).apply {
            text = rowUi.viewName?.let { evaluateViewName(it) } ?: rowUi.name
            textSize = 14f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener { executeButtonAction(rowUi, false) }
            setOnLongClickListener { executeButtonAction(rowUi, true); true }
        }
        if (!rowUi.viewName.isNullOrBlank()) {
            viewNameButtons[rowUi.name] = button
        }
        addView(button)
    }

    /**
     * 执行文本输入的 action（用户完成输入后执行JS）
     * 使用 source.evalJS 确保 jsLib 函数可用
     */
    private fun executeInputAction(fieldName: String, action: String, inputValue: String) {
        try {
            val src = source ?: return
            val bridge = loginJsBridge ?: LoginJsBridge(null, src.bookSourceUrl, bookSource = src)
            bridge.loginData = collectFormData().toMutableMap()
            val loginJs = src.getLoginJs() ?: ""
            val fieldJson = gson.toJson(fieldName)
            val valueJson = gson.toJson(inputValue)
            // 不用 var result = {}，让 bindings 注入的 result (JavaMap) 生效
            val fullJs = """
                $loginJs
                result.get = function(key) { return java.fetchLoginData()[key] || ""; };
                result[$fieldJson] = $valueJson;
                $action
            """.trimIndent()
            src.evalJS(fullJs) { b ->
                b["java"] = bridge; b["source"] = bridge; b["baseSource"] = bridge
                b["result"] = collectFormData().toMutableMap()
            }
        } catch (e: Exception) {
            android.util.Log.e("LoginActivity", "输入 action 执行失败: $fieldName", e)
        }
    }

    /**
     * 执行按钮 action（使用 source.evalJS 加载 jsLib）
     */
    private fun executeButtonAction(rowUi: RowUi, isLongClick: Boolean) {
        val action = rowUi.action
        val src = source ?: return
        if (action.isNullOrBlank()) return

        when {
            action.startsWith("http://") || action.startsWith("https://") -> {
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action))) } catch (_: Exception) {}
            }
            else -> {
                val formData = collectFormData().toMutableMap()
                Thread {
                    try {
                        val bridge = loginJsBridge ?: LoginJsBridge(null, src.bookSourceUrl, bookSource = src)
                        bridge.loginData = formData.toMutableMap()
                        val loginJs = src.getLoginJs() ?: ""
                        val fullJs = """
                            $loginJs
                            result.get = function(key) { return java.fetchLoginData()[key] || ""; };
                            var isLongClick = $isLongClick;
                            $action
                        """.trimIndent()
                        src.evalJS(fullJs) { b ->
                            b["java"] = bridge; b["source"] = bridge; b["baseSource"] = bridge
                            b["result"] = formData.toMutableMap()
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(this, "按钮 ${rowUi.name} 执行失败: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }
        }
    }

    /**
     * 评估 viewName JS 表达式
     * 例如: "book?.name||'未获取到书名'" 或 "'排序按钮别名'"
     */
    private fun evaluateViewName(viewName: String): String {
        return try {
            val src = source ?: return viewName
            val bridge = loginJsBridge ?: LoginJsBridge(null, src.bookSourceUrl, bookSource = src)
            bridge.loginData = collectFormData().toMutableMap()
            val result = src.evalJS(viewName) { b ->
                b["java"] = bridge; b["source"] = bridge; b["baseSource"] = bridge
            }
            result?.toString()?.takeIf { it.isNotBlank() } ?: viewName
        } catch (_: Exception) { viewName }
    }

    /**
     * 刷新所有 viewName 按钮的文本
     */
    private fun updateViewNameButtons() {
        val rowUis = rowUis ?: return
        for (rowUi in rowUis) {
            if (rowUi.type == RowUi.Type.button && !rowUi.viewName.isNullOrBlank()) {
                val button = viewNameButtons[rowUi.name] ?: continue
                button.text = evaluateViewName(rowUi.viewName!!)
            }
        }
    }

    // =========================================================================
    // 模式二：WebView 登录
    // =========================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewLogin(source: BookSource, loginUrl: String) {
        useWebViewMode = true
        scrollForm.visibility = View.GONE
        webviewContainer.visibility = View.VISIBLE

        val actualUrl = when {
            loginUrl.startsWith("@js:", true) -> evalLoginJs(loginUrl.substring(4))?.toString() ?: loginUrl
            loginUrl.startsWith("<js>", true) -> evalLoginJs(loginUrl.substring(4, loginUrl.lastIndexOf("<")))?.toString() ?: loginUrl
            else -> loginUrl
        }

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = io.legado.engine.constant.AppConst.USER_AGENT
            // 支持双指缩放
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            addJavascriptInterface(loginJsBridge!!, "java")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // 注入 baseSource/source/java
                    try {
                        val injectJs = """
                            if (typeof java === 'undefined') { window.java = {}; }
                            if (typeof source === 'undefined') { window.source = java; }
                            if (typeof baseSource === 'undefined') { window.baseSource = java; }
                        """.trimIndent()
                        view?.evaluateJavascript(injectJs, null)
                    } catch (_: Exception) {}
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            webChromeClient = WebChromeClient()
            if (actualUrl.startsWith("http://") || actualUrl.startsWith("https://")) loadUrl(actualUrl) else loadData(actualUrl, "text/html", "UTF-8")
        }
        webviewContainer.addView(webView)
    }

    // =========================================================================
    // LoginJsBridge.Callback
    // =========================================================================

    override fun upLoginData(data: Map<String, Any?>?) {
        data?.forEach { (key, value) ->
            val view = formViews[key] ?: return@forEach
            val strValue = value?.toString() ?: ""
            when (view) {
                is EditText -> view.setText(strValue)
                is Switch -> view.isChecked = strValue == "true"
                is Spinner -> {
                    val adapter = view.adapter as? ArrayAdapter<String>
                    if (adapter != null) {
                        for (i in 0 until adapter.count) {
                            if (adapter.getItem(i) == strValue) { view.setSelection(i); break }
                        }
                    }
                }
            }
        }
        updateViewNameButtons()
    }

    override fun reLoginView(deltaUp: Boolean) {
        val src = source ?: return
        scrollForm.removeAllViews()
        formContainer.removeAllViews()
        formViews.clear()
        saveCheckboxes.clear()
        viewNameButtons.clear()
        initFormLogin(src, src.loginUi ?: return)
    }

    // =========================================================================
    // 表单数据收集与保存
    // =========================================================================

    private fun collectFormData(): Map<String, String> {
        val data = mutableMapOf<String, String>()
        rowUis?.forEach { rowUi ->
            if (rowUi.type != RowUi.Type.button) {
                val view = formViews[rowUi.name]
                val value = when (view) {
                    is EditText -> view.text?.toString() ?: ""
                    is Switch -> if (view.isChecked) (rowUi.chars?.getOrNull(1) ?: "true") else (rowUi.chars?.getOrNull(0) ?: "false")
                    is Spinner -> view.selectedItem?.toString() ?: ""
                    else -> ""
                }
                data[rowUi.name] = value
            }
        }
        return data
    }

    /**
     * 仅保存勾选了保存的数据
     */
    private fun collectSaveableData(): Map<String, String> {
        val data = mutableMapOf<String, String>()
        rowUis?.forEach { rowUi ->
            if (rowUi.type != RowUi.Type.button) {
                val shouldSave = when (rowUi.type) {
                    RowUi.Type.text, RowUi.Type.password -> saveCheckboxes[rowUi.name]?.isChecked == true
                    else -> true
                }
                if (shouldSave) {
                    val view = formViews[rowUi.name]
                    val value = when (view) {
                        is EditText -> view.text?.toString() ?: ""
                        is Switch -> if (view.isChecked) (rowUi.chars?.getOrNull(1) ?: "true") else (rowUi.chars?.getOrNull(0) ?: "false")
                        is Spinner -> view.selectedItem?.toString() ?: ""
                        else -> ""
                    }
                    data[rowUi.name] = value
                }
            }
        }
        return data
    }

    private fun getLoginInfo(source: BookSource): Map<String, String> {
        return try {
            source.getLoginInfoMap().takeIf { it.isNotEmpty() }?.let { return it }
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = prefs.getString("info_${source.bookSourceUrl}", null)
            if (json != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                gson.fromJson(json, type) ?: emptyMap()
            } else emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveLoginInfo(source: BookSource, data: Map<String, String>) {
        try {
            val merged = getLoginInfo(source).toMutableMap().apply { putAll(data) }
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val json = gson.toJson(merged)
            prefs.edit().putString("info_${source.bookSourceUrl}", json).apply()
            source.putLoginInfo(json)
        } catch (_: Exception) {}
    }

    private fun clearLoginInfo(source: BookSource) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            prefs.edit().remove("info_${source.bookSourceUrl}").apply()
            source.removeLoginInfo()
            val domain = try { java.net.URL(source.bookSourceUrl).host } catch (_: Exception) { return }
            CookieStore.clear(domain)
        } catch (_: Exception) {}
    }

    /**
     * 保存并退出
     */
    private fun saveAndFinish() {
        val src = source ?: return
        val saveableData = collectSaveableData()
        if (saveableData.isNotEmpty()) {
            saveLoginInfo(src, saveableData)
        }
        Toast.makeText(this, "登录信息已保存", Toast.LENGTH_SHORT).show()
        finish()
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private fun evalLoginJs(jsStr: String): Any? {
        val src = source ?: return null
        return try {
            src.evalJS(jsStr)
        } catch (_: Exception) { null }
    }

    private fun resolveLoginUiJson(loginUiStr: String): String {
        val evaluated = when {
            loginUiStr.startsWith("@js:", true) -> evalLoginJs(loginUiStr.substring(4))
            loginUiStr.startsWith("<js>", true) -> {
                val end = loginUiStr.lastIndexOf("<").takeIf { it > 4 } ?: loginUiStr.length
                evalLoginJs(loginUiStr.substring(4, end))
            }
            else -> return loginUiStr
        }
        return when (evaluated) {
            null -> loginUiStr
            is CharSequence -> evaluated.toString()
            is Map<*, *>, is Iterable<*>, is Array<*> -> gson.toJson(evaluated)
            else -> evaluated.toString()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (useWebViewMode && webView?.canGoBack() == true) { webView?.goBack() }
        else {
            if (hasFormChanges) {
                val src = source
                if (src != null) {
                    val saveableData = collectSaveableData()
                    if (saveableData.isNotEmpty()) saveLoginInfo(src, saveableData)
                }
            }
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.destroy(); webView = null
        super.onDestroy()
    }
}
