package io.legado.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import io.legado.engine.http.CookieStore
import io.legado.engine.data.BookSource
import io.legado.engine.shim.CacheManager
import io.legado.engine.model.RowUi
import com.google.gson.Gson
import io.legado.plugin.R
import com.google.gson.reflect.TypeToken
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.ScriptableObject

class LoginActivity : Activity(), LoginJsBridge.Callback {

    companion object {
        private const val ACTION_DEBOUNCE_MS = 600L
        const val EXTRA_SOURCE_JSON = "source_json"
        private const val PREFS_NAME = "legado_login_info"
        private val gson = Gson()
        fun newIntent(context: Context, sourceJson: String): Intent = Intent().apply {
            setClassName("io.legado.plugin", LoginActivity::class.java.name)
            putExtra(EXTRA_SOURCE_JSON, sourceJson); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        fun start(context: Context, source: BookSource) { context.startActivity(newIntent(context, gson.toJson(source))) }
    }

    private lateinit var tvTitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var scrollForm: ScrollView
    private lateinit var webviewContainer: FrameLayout
    private lateinit var btnSave: Button
    private var source: BookSource? = null
    private var rowUis: List<RowUi>? = null
    private var webView: WebView? = null
    private var loginJsBridge: LoginJsBridge? = null
    private var hasFormChanges = false
    private var useWebViewMode = false
    private val handler by lazy { Handler(mainLooper) }
    private val formViews = mutableMapOf<String, View>()
    private val saveCheckboxes = mutableMapOf<String, CheckBox>()
    private val viewNameButtons = mutableMapOf<String, Button>()
    private val toggleViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        tvTitle = findViewById(R.id.tv_title)
        btnBack = findViewById(R.id.btn_back)
        scrollForm = findViewById(R.id.scroll_form)
        webviewContainer = findViewById(R.id.webview_container)
        btnSave = Button(this).apply { text = "\u4fdd\u5b58\u767b\u5f55\u4fe1\u606f"; visibility = View.GONE; setOnClickListener { saveAndFinish() } }
        btnBack.setOnClickListener { onBackPressed() }
        val json = intent.getStringExtra(EXTRA_SOURCE_JSON) ?: run { finish(); return }
        source = try { gson.fromJson(json, BookSource::class.java) } catch (_: Exception) { null } ?: run { finish(); return }
        val src = source!!
        tvTitle.text = "\u767b\u5f55 - ${src.bookSourceName.ifBlank { src.bookSourceUrl }}"
        loginJsBridge = LoginJsBridge(this, src.bookSourceUrl, this, src)
        val loginUiStr = src.loginUi; val loginUrl = src.loginUrl
        when {
            !loginUiStr.isNullOrBlank() -> initFormLogin(src, loginUiStr)
            !loginUrl.isNullOrBlank() -> initWebViewLogin(src, loginUrl)
            else -> { Toast.makeText(this, "\u8be5\u4e66\u6e90\u672a\u914d\u7f6e\u767b\u5f55", Toast.LENGTH_SHORT).show(); finish() }
        }
    }

    private fun initFormLogin(source: BookSource, loginUiStr: String) {
        useWebViewMode = false; scrollForm.visibility = View.VISIBLE; webviewContainer.visibility = View.GONE
        val actualJson = resolveLoginUiJson(loginUiStr)
        val type = object : TypeToken<List<RowUi>>() {}.type
        rowUis = try { gson.fromJson(actualJson, type) } catch (_: Exception) { null }
        if (rowUis.isNullOrEmpty()) { Toast.makeText(this, "loginUi \u89e3\u6790\u5931\u8d25", Toast.LENGTH_SHORT).show(); finish(); return }
        val savedInfo = getLoginInfo(source)
        val cl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        for (r in rowUis!!) {
            when (r.type) {
                RowUi.Type.text, RowUi.Type.password -> cl.addTextInput(r, savedInfo)
                RowUi.Type.toggle -> cl.addToggle(r, savedInfo)
                RowUi.Type.select -> cl.addSelect(r, savedInfo)
                RowUi.Type.button -> cl.addButton(r)
            }
        }
        cl.addView(btnSave, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })
        scrollForm.addView(cl); btnSave.visibility = View.VISIBLE; resolveAllViewNames()
    }

    private fun LinearLayout.addTextInput(rowUi: RowUi, savedInfo: Map<String, String>) {
        val lr = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val lbl = TextView(context).apply {
            text = resolveViewNameStatic(rowUi); textSize = 14f; setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        lr.addView(lbl)
        val cb = CheckBox(context).apply { text = "\u4fdd\u5b58"; textSize = 12f; isChecked = false; setOnCheckedChangeListener { _, _ -> hasFormChanges = true } }
        saveCheckboxes[rowUi.name] = cb; lr.addView(cb); addView(lr)
        val et = EditText(context).apply {
            hint = rowUi.default ?: ""
            inputType = if (rowUi.type == RowUi.Type.password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT
            setText(savedInfo[rowUi.name] ?: rowUi.default ?: "")
            setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundResource(android.R.drawable.edit_text)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { hasFormChanges = true }
                override fun afterTextChanged(s: Editable?) {
                    if (!rowUi.action.isNullOrBlank()) {
                        handler.removeCallbacksAndMessages("i_${rowUi.name}")
                        handler.postDelayed({ executeInputAction(rowUi.name, rowUi.action!!, s?.toString() ?: "") }, "i_${rowUi.name}", ACTION_DEBOUNCE_MS)
                    }
                }
            })
        }
        formViews[rowUi.name] = et; addView(et)
        // \u5f02\u6b65\u89e3\u6790 viewName JS \u8868\u8fbe\u5f0f
        if (rowUi.viewName != null && !(rowUi.viewName!!.length in 3..19 && rowUi.viewName!!.first() == '\'' && rowUi.viewName!!.last() == '\'')) {
            Thread { val r = evalUiJs(rowUi.viewName!!); runOnUiThread { lbl.text = if (r.isNullOrEmpty()) "null" else r } }.start()
        }
    }

    private fun LinearLayout.addToggle(rowUi: RowUi, savedInfo: Map<String, String>) {
        val chars = rowUi.chars?.filterNotNull() ?: listOf("chars is null")
        val left = true // \u9ed8\u8ba4\u5de6\u4fa7\u663e\u793a\u5b57\u7b26
        val cur = savedInfo[rowUi.name]?.ifEmpty { rowUi.default ?: chars[0] } ?: (rowUi.default ?: chars[0])
        val dn = resolveViewNameStatic(rowUi)
        var curChar = cur
        val tv = TextView(context).apply {
            text = if (left) curChar + dn else dn + curChar; textSize = 14f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            isClickable = true
            setOnClickListener {
                val idx = chars.indexOf(curChar); curChar = chars[(idx + 1) % chars.size]; hasFormChanges = true
                text = if (left) curChar + dn else dn + curChar
                if (!rowUi.action.isNullOrBlank()) executeButtonAction(rowUi, false)
            }
        }
        formViews[rowUi.name] = tv; toggleViews[rowUi.name] = tv; addView(tv)
        if (rowUi.viewName != null && !(rowUi.viewName!!.length in 3..19 && rowUi.viewName!!.first() == '\'' && rowUi.viewName!!.last() == '\'')) {
            Thread { val r = evalUiJs(rowUi.viewName!!); runOnUiThread { if (!r.isNullOrEmpty()) tv.text = if (left) curChar + r else r + curChar } }.start()
        }
    }

    private fun LinearLayout.addSelect(rowUi: RowUi, savedInfo: Map<String, String>) {
        addView(TextView(context).apply { text = rowUi.name; textSize = 14f; setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(4), dp(8), dp(4)) })
        val opts = rowUi.chars?.filterNotNull()?.toTypedArray() ?: arrayOf()
        val sp = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, opts)
            val di = opts.indexOfFirst { it == (savedInfo[rowUi.name] ?: rowUi.default) }; if (di >= 0) setSelection(di)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener { override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { hasFormChanges = true }; override fun onNothingSelected(p: AdapterView<*>?) {} }
        }
        formViews[rowUi.name] = sp; addView(sp)
    }

    private fun LinearLayout.addButton(rowUi: RowUi) {
        val btn = Button(context).apply {
            text = resolveViewNameStatic(rowUi); textSize = 14f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            setOnClickListener { executeButtonAction(rowUi, false) }
            setOnLongClickListener { executeButtonAction(rowUi, true); true }
        }
        if (!rowUi.viewName.isNullOrBlank()) viewNameButtons[rowUi.name] = btn
        addView(btn)
    }

    // ==================== JS \u6267\u884c\u4e0a\u4e0b\u6587 ====================

    private fun buildLoginScope(bridge: LoginJsBridge): Pair<RhinoContext, org.mozilla.javascript.Scriptable> {
        val cx = RhinoContext.enter(); cx.optimizationLevel = -1; val scope = cx.initStandardObjects()
        bridge.loginData = collectFormData().toMutableMap()
        ScriptableObject.putProperty(scope, "java", RhinoContext.javaToJS(bridge, scope))
        ScriptableObject.putProperty(scope, "source", RhinoContext.javaToJS(bridge, scope))
        ScriptableObject.putProperty(scope, "cookie", RhinoContext.javaToJS(CookieStore, scope))
        ScriptableObject.putProperty(scope, "cache", RhinoContext.javaToJS(CacheManager, scope))
        ScriptableObject.putProperty(scope, "book", null)
        ScriptableObject.putProperty(scope, "chapter", null)
        ScriptableObject.putProperty(scope, "loginUrl", source?.loginUrl ?: "")
        val sharedScope = source?.jsLib?.let { io.legado.engine.js.SharedJsScope.getScope(it) }
        if (sharedScope != null) { scope.prototype = sharedScope }
        val resultObj = cx.newObject(scope)
        collectFormData().forEach { (k, v) -> ScriptableObject.putProperty(resultObj, k, v) }
        ScriptableObject.putProperty(scope, "result", resultObj)
        return Pair(cx, scope)
    }

    private fun evalUiJs(jsStr: String): String? {
        val src = source ?: return null
        val bridge = loginJsBridge ?: LoginJsBridge(null, src.bookSourceUrl, bookSource = src)
        return try {
            val loginJs = src.getLoginJs() ?: ""
            val (cx, scope) = buildLoginScope(bridge)
            try { cx.evaluateString(scope, "$loginJs\n$jsStr", "evalUiJs", 1, null)?.toString() } finally { RhinoContext.exit() }
        } catch (e: Exception) { android.util.Log.e("LoginActivity", "evalUiJs error", e); null }
    }

    private fun resolveLoginUiJson(loginUiStr: String): String {
        val src = source ?: return loginUiStr
        val loginJs = src.getLoginJs() ?: ""
        val evaluated = when {
            loginUiStr.startsWith("@js:", true) -> evalUiJs(loginUiStr.substring(4))
            loginUiStr.startsWith("<js>", true) -> { val end = loginUiStr.lastIndexOf("<").takeIf { it > 4 } ?: loginUiStr.length; evalUiJs(loginUiStr.substring(4, end)) }
            else -> return loginUiStr
        }
        return when (evaluated) { null -> loginUiStr; is CharSequence -> evaluated.toString(); is Map<*,*>, is Iterable<*>, is Array<*> -> gson.toJson(evaluated); else -> evaluated.toString() }
    }

    private fun resolveViewNameStatic(rowUi: RowUi): String {
        val vn = rowUi.viewName ?: return rowUi.name
        return if (vn.length in 3..19 && vn.first() == '\'' && vn.last() == '\'') vn.substring(1, vn.length - 1) else rowUi.name
    }

    private fun resolveAllViewNames() {
        val uis = rowUis ?: return
        for (r in uis) {
            val vn = r.viewName ?: continue
            if (vn.length in 3..19 && vn.first() == '\'' && vn.last() == '\'') continue
            when (r.type) {
                RowUi.Type.button -> { val btn = viewNameButtons[r.name] ?: continue; Thread { val res = evalUiJs(vn); runOnUiThread { btn.text = if (res.isNullOrEmpty()) "null" else res; r.viewName = res } }.start() }
                RowUi.Type.toggle -> { val tv = toggleViews[r.name] ?: continue; val chars = r.chars?.filterNotNull() ?: listOf("x"); Thread { val res = evalUiJs(vn); runOnUiThread { if (!res.isNullOrEmpty()) { r.viewName = res; tv.text = chars[0] + res } } }.start() }
                RowUi.Type.text, RowUi.Type.password -> { val et = formViews[r.name] as? EditText ?: continue; Thread { val res = evalUiJs(vn); runOnUiThread { et.hint = if (res.isNullOrEmpty()) "null" else res } }.start() }
            }
        }
    }

    // ==================== Button / Input action ====================

    private fun executeButtonAction(rowUi: RowUi, isLongClick: Boolean) {
        val action = rowUi.action; val src = source ?: return
        if (action.isNullOrBlank()) return
        when {
            action.startsWith("http://") || action.startsWith("https://") -> { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(action))) } catch (_: Exception) {} }
            else -> {
                Thread {
                    try {
                        val bridge = loginJsBridge ?: LoginJsBridge(this, src.bookSourceUrl, this, src)
                        val loginJs = src.getLoginJs() ?: ""
                        val (cx, scope) = buildLoginScope(bridge)
                        ScriptableObject.putProperty(scope, "isLongClick", isLongClick)
                        try { cx.evaluateString(scope, "$loginJs\n$action", "btn_${rowUi.name}", 1, null) } finally { RhinoContext.exit() }
                    } catch (e: Exception) { runOnUiThread { Toast.makeText(this, "\u6309\u94ae ${rowUi.name} \u6267\u884c\u5931\u8d25: ${e.message}", Toast.LENGTH_LONG).show() } }
                }.start()
            }
        }
    }

    private fun executeInputAction(fieldName: String, action: String, inputValue: String) {
        try {
            val src = source ?: return
            val bridge = loginJsBridge ?: LoginJsBridge(this, src.bookSourceUrl, bookSource = src)
            val loginJs = src.getLoginJs() ?: ""
            val (cx, scope) = buildLoginScope(bridge)
            val resultObj = cx.newObject(scope)
            collectFormData().forEach { (k, v) -> ScriptableObject.putProperty(resultObj, k, v) }
            ScriptableObject.putProperty(resultObj, fieldName, inputValue)
            ScriptableObject.putProperty(scope, "result", resultObj)
            try { cx.evaluateString(scope, "$loginJs\n$action", "input_$fieldName", 1, null) } finally { RhinoContext.exit() }
        } catch (e: Exception) { android.util.Log.e("LoginActivity", "input action failed: $fieldName", e) }
    }

    // ==================== Callback ====================

    override fun upLoginData(data: Map<String, Any?>?) {
        runOnUiThread {
            if (data == null) {
                rowUis?.forEach { r -> val v = formViews[r.name]; when (v) { is EditText -> v.setText(r.default ?: ""); is TextView -> { if (r.type == RowUi.Type.toggle) { val chars = r.chars?.filterNotNull() ?: listOf("x"); val c = r.default ?: chars[0]; v.text = c + resolveViewNameStatic(r) } }; is Spinner -> { val chars = r.chars?.filterNotNull() ?: listOf<String>(); val i = chars.indexOf(r.default); if (i >= 0) v.setSelection(i) } } }
                updateViewNameButtons(); return@runOnUiThread
            }
            data.forEach { (key, value) ->
                val sv = value?.toString() ?: return@forEach
                val r = rowUis?.firstOrNull { it.name == key }
                if (r == null) { loginJsBridge?.loginData?.put(key, sv); return@forEach }
                val v = formViews[key]
                when (v) {
                    is EditText -> v.setText(sv)
                    is TextView -> when (r.type) { RowUi.Type.button -> v.text = sv.ifEmpty { r.viewName ?: key }; RowUi.Type.toggle -> { val chars = r.chars?.filterNotNull() ?: listOf("x"); v.text = sv.ifEmpty { chars[0] } + resolveViewNameStatic(r) } }
                    is Spinner -> { val a = v.adapter as? ArrayAdapter<String>; if (a != null) for (i in 0 until a.count) { if (a.getItem(i) == sv) { v.setSelection(i); break } } }
                }
            }
            updateViewNameButtons()
        }
    }

    override fun reLoginView(deltaUp: Boolean) {
        val src = source ?: return; val loginUiStr = src.loginUi ?: return
        val isJs = loginUiStr.startsWith("@js:", true) || loginUiStr.startsWith("<js>", true)
        val doUpdate: (List<RowUi>?) -> Unit = { newUis ->
            if (deltaUp && newUis != null && rowUis != null) {
                val currentData = collectFormData()
                rowUis = newUis; rebuildForm(src)
                currentData.forEach { (k, v) -> if (newUis.any { it.name == k }) { val view = formViews[k]; when (view) { is EditText -> view.setText(v); is Spinner -> { val a = view.adapter as? ArrayAdapter<String>; if (a != null) for (idx in 0 until a.count) { if (a.getItem(idx) == v) { view.setSelection(idx); break } } } } } }
            } else { rowUis = newUis; rebuildForm(src) }
        }
        if (isJs) {
            Thread {
                val newJson = resolveLoginUiJson(loginUiStr); val type = object : TypeToken<List<RowUi>>() {}.type
                val newUis = try { gson.fromJson<List<RowUi>>(newJson, type) } catch (_: Exception) { null }
                runOnUiThread { doUpdate(newUis) }
            }.start()
        } else {
            val newUis = try { gson.fromJson<List<RowUi>>(loginUiStr, object : TypeToken<List<RowUi>>() {}.type) } catch (_: Exception) { null }
            doUpdate(newUis)
        }
    }

    private fun rebuildForm(source: BookSource) {
        scrollForm.removeAllViews(); formViews.clear(); saveCheckboxes.clear(); viewNameButtons.clear(); toggleViews.clear()
        initFormLogin(source, source.loginUi ?: return)
    }

    // ==================== \u8868\u5355\u6570\u636e ====================

    private fun collectFormData(): Map<String, String> {
        val d = mutableMapOf<String, String>()
        rowUis?.forEach { r -> if (r.type != RowUi.Type.button) { val v = formViews[r.name]; d[r.name] = when (v) { is EditText -> v.text?.toString() ?: ""; is TextView -> { if (r.type == RowUi.Type.toggle) { val chars = r.chars?.filterNotNull() ?: listOf("x"); chars.firstOrNull { v.text.toString().contains(it) } ?: r.default ?: chars[0] } else "" }; is Spinner -> v.selectedItem?.toString() ?: ""; else -> "" } } }
        return d
    }

    private fun collectSaveableData(): Map<String, String> {
        val d = mutableMapOf<String, String>()
        rowUis?.forEach { r -> if (r.type != RowUi.Type.button) { val shouldSave = when (r.type) { RowUi.Type.text, RowUi.Type.password -> saveCheckboxes[r.name]?.isChecked == true; else -> true }; if (shouldSave) { val v = formViews[r.name]; d[r.name] = when (v) { is EditText -> v.text?.toString() ?: ""; is TextView -> { if (r.type == RowUi.Type.toggle) { val chars = r.chars?.filterNotNull() ?: listOf("x"); chars.firstOrNull { v.text.toString().contains(it) } ?: r.default ?: chars[0] } else "" }; is Spinner -> v.selectedItem?.toString() ?: ""; else -> "" } } } }
        return d
    }

    private fun getLoginInfo(source: BookSource): Map<String, String> {
        return try { source.getLoginInfoMap().takeIf { it.isNotEmpty() }?.let { return it }; val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); val j = p.getString("info_${source.bookSourceUrl}", null); if (j != null) { val t = object : TypeToken<Map<String, String>>() {}.type; gson.fromJson(j, t) ?: emptyMap() } else emptyMap() } catch (_: Exception) { emptyMap() }
    }

    private fun saveLoginInfo(source: BookSource, data: Map<String, String>) {
        try { val m = getLoginInfo(source).toMutableMap().apply { putAll(data) }; val p = getSharedPreferences(PREFS_NAME, MODE_PRIVATE); val j = gson.toJson(m); p.edit().putString("info_${source.bookSourceUrl}", j).apply(); source.putLoginInfo(j) } catch (_: Exception) {}
    }

    private fun saveAndFinish() { val src = source ?: return; val d = collectSaveableData(); if (d.isNotEmpty()) saveLoginInfo(src, d); Toast.makeText(this, "\u767b\u5f55\u4fe1\u606f\u5df2\u4fdd\u5b58", Toast.LENGTH_SHORT).show(); finish() }

    private fun updateViewNameButtons() {
        val uis = rowUis ?: return
        for (r in uis) { if (r.type == RowUi.Type.button && !r.viewName.isNullOrBlank()) { val btn = viewNameButtons[r.name] ?: continue; val vn = r.viewName!!; if (vn.length in 3..19 && vn.first() == '\'' && vn.last() == '\'') btn.text = vn.substring(1, vn.length - 1) } }
    }

    // ==================== WebView \u767b\u5f55 ====================

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebViewLogin(source: BookSource, loginUrl: String) {
        useWebViewMode = true; scrollForm.visibility = View.GONE; webviewContainer.visibility = View.VISIBLE
        val actualUrl = when {
            loginUrl.startsWith("@js:", true) -> evalUiJs(loginUrl.substring(4))?.toString() ?: loginUrl
            loginUrl.startsWith("<js>", true) -> evalUiJs(loginUrl.substring(4, loginUrl.lastIndexOf("<")))?.toString() ?: loginUrl
            else -> loginUrl
        }
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.userAgentString = io.legado.engine.constant.AppConst.USER_AGENT
            val webBridge = LegadoWebBridge(loginJsBridge!!)
            val cacheBridge = LegadoCacheWebBridge()
            addJavascriptInterface(webBridge, "java")
            addJavascriptInterface(webBridge, "source")
            addJavascriptInterface(cacheBridge, "cache")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    try { view?.evaluateJavascript(legadoWebBootstrapScript(source.jsLib, source.bookSourceUrl, url ?: actualUrl), null) } catch (_: Exception) {}
                    url?.let { val c = CookieManager.getInstance().getCookie(it); if (!c.isNullOrBlank()) CookieStore.setCookie(source.bookSourceUrl, c) }
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            }
            webChromeClient = WebChromeClient()
            if (actualUrl.startsWith("http://") || actualUrl.startsWith("https://")) {
                loadUrl(actualUrl)
            } else {
                val html = injectLegadoWebBootstrap(actualUrl, source.jsLib, source.bookSourceUrl, source.bookSourceUrl)
                loadDataWithBaseURL(source.bookSourceUrl, html, "text/html", "UTF-8", null)
            }
        }
        webviewContainer.addView(webView)
    }

    // ==================== \u8f85\u52a9 ====================

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onBackPressed() {
        if (useWebViewMode && webView?.canGoBack() == true) { webView?.goBack() }
        else { if (hasFormChanges) { source?.let { val d = collectSaveableData(); if (d.isNotEmpty()) saveLoginInfo(it, d) } }; super.onBackPressed() }
    }

    override fun onDestroy() { handler.removeCallbacksAndMessages(null); webView?.destroy(); webView = null; super.onDestroy() }
}
