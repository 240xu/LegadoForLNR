package io.legado.engine.data

import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.engine.constant.AppConst
import io.legado.engine.http.CookieStore
import io.legado.engine.js.SharedJsScope
import io.legado.engine.js.SourceLoginCallback
import io.legado.engine.model.RowUi
import io.legado.engine.rule.UrlOptionParser
import io.legado.engine.shim.CacheManager
import io.legado.engine.shim.Debug
import io.legado.engine.shim.GSON
import io.legado.engine.shim.GSONStrict
import io.legado.engine.shim.fromJsonArray
import io.legado.engine.shim.fromJsonObject
import io.legado.engine.js.JsExtensions

interface BaseSource : JsExtensions {
    var concurrentRate: String?
    var loginUrl: String?
    var loginUi: String?
    var header: String?
    var enabledCookieJar: Boolean?
    var jsLib: String?
    override fun getTag(): String
    fun getKey(): String
    override fun getSource(): BaseSource? = this

    fun getLoginJs(): String? {
        val loginJs = loginUrl
        return when {
            loginJs == null -> null
            loginJs.startsWith("@js:") -> loginJs.substring(4)
            loginJs.startsWith("<js>") -> loginJs.substring(4, loginJs.lastIndexOf("<"))
            else -> loginJs
        }
    }

    fun login() {
        if (!loginUi.isNullOrBlank()) {
            SourceLoginCallback.requestLogin(this)
        }
        val loginJs = getLoginJs()
        if (!loginJs.isNullOrBlank()) {
            val js = """$loginJs
                if(typeof login=='function'){
                    login.apply(this);
                } else {
                    throw('Function login not implements!!!')
                }
            """.trimIndent()
            evalJS(js)
        }
    }

    fun getHeaderMap(hasLoginHeader: Boolean = false): HashMap<String, String> {
        return HashMap<String, String>().apply {
            header?.let {
                try {
                    val json = when {
                        it.startsWith("@js:", true) -> evalJS(it.substring(4)).toString()
                        it.startsWith("<js>", true) -> evalJS(it.substring(4, it.lastIndexOf("<"))).toString()
                        else -> it
                    }
                    GSONStrict.fromJsonObject<Map<String, String>>(json)?.let { m -> putAll(m) }
                        ?: GSON.fromJsonObject<Map<String, String>>(json)?.let { m -> putAll(m) }
                        ?: putAll(UrlOptionParser.parseHeaderLines(json))
                } catch (e: Exception) { Debug.log("header error: " + e.message) }
            }
            if (!containsKey("User-Agent")) put("User-Agent", AppConst.USER_AGENT)
            if (hasLoginHeader) getLoginHeaderMap()?.let { putAll(it) }
        }
    }

    fun getLoginHeader(): String? = CacheManager.get("loginHeader_" + getKey())
    fun getLoginHeaderMap(): Map<String, String>? {
        val cache = getLoginHeader() ?: return null
        return GSON.fromJsonObject<Map<String, String>>(cache)
    }
    fun putLoginHeader(header: String) {
        val headerMap = GSON.fromJsonObject<Map<String, String>>(header)
        val cookie = headerMap?.get("Cookie") ?: headerMap?.get("cookie")
        cookie?.let { CookieStore.replaceCookie(getKey(), it) }
        CacheManager.put("loginHeader_" + getKey(), header)
    }
    fun removeLoginHeader() { CacheManager.delete("loginHeader_" + getKey()); CookieStore.removeCookie(getKey()) }

    /**
     * 对齐 lyc486: AES 解密读取登录信息
     */
    fun getLoginInfo(): String? {
        return try {
            val cache = CacheManager.get("userInfo_" + getKey()) ?: return null
            try {
                val key = io.legado.engine.constant.AppConst.androidId.encodeToByteArray().copyOf(16)
                val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
                String(cipher.doFinal(java.util.Base64.getDecoder().decode(cache)))
            } catch (_: Exception) {
                try { String(java.util.Base64.getDecoder().decode(cache)) } catch (_: Exception) { cache }
            }
        } catch (_: Exception) { null }
    }
    fun getLoginInfoMap(): MutableMap<String, String> {
        val json = getLoginInfo()
        if (json != null) return GSON.fromJsonObject<MutableMap<String, String>>(json) ?: mutableMapOf()
        val defaults = getLoginUiDefaultInfo()
        if (defaults.isNotEmpty()) {
            putLoginInfo(GSON.toJson(defaults))
        }
        return defaults.toMutableMap()
    }

    private fun getLoginUiDefaultInfo(): Map<String, String> {
        val loginUiJson = resolveLoginUiJsonForDefaults() ?: return emptyMap()
        return GSON.fromJsonArray<RowUi>(loginUiJson)
            ?.filter { it.type != RowUi.Type.button }
            ?.associate { it.name to (it.default ?: "") }
            ?.filterKeys { it.isNotBlank() }
            ?: emptyMap()
    }

    private fun resolveLoginUiJsonForDefaults(): String? {
        val raw = loginUi?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val evaluated = when {
                raw.startsWith("@js:", true) -> evalJS("${getLoginJs() ?: ""}\n${raw.substring(4)}")
                raw.startsWith("<js>", true) -> {
                    val end = raw.lastIndexOf("<").takeIf { it > 4 } ?: raw.length
                    evalJS("${getLoginJs() ?: ""}\n${raw.substring(4, end)}")
                }
                else -> return raw
            }
            when (evaluated) {
                null -> raw
                is CharSequence -> evaluated.toString()
                is Map<*, *>, is Iterable<*>, is Array<*> -> GSON.toJson(evaluated)
                else -> evaluated.toString()
            }
        } catch (e: Exception) {
            Debug.log("loginUi default parse error: " + e.message)
            null
        }
    }
    /**
     * 对齐 lyc486: 使用 AES 加密存储登录信息
     */
    fun putLoginInfo(info: String): Boolean {
        return try {
            val key = io.legado.engine.constant.AppConst.androidId.encodeToByteArray().copyOf(16)
            val cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
            val encoded = java.util.Base64.getEncoder().encodeToString(cipher.doFinal(info.toByteArray()))
            CacheManager.put("userInfo_" + getKey(), encoded)
            true
        } catch (_: Exception) {
            try {
                val encoded = java.util.Base64.getEncoder().encodeToString(info.toByteArray())
                CacheManager.put("userInfo_" + getKey(), encoded)
                true
            } catch (_: Exception) { false }
        }
    }
    fun removeLoginInfo() { CacheManager.delete("userInfo_" + getKey()) }

    fun setVariable(variable: String?) {
        if (variable != null) CacheManager.put("sourceVariable_" + getKey(), variable)
        else CacheManager.delete("sourceVariable_" + getKey())
    }
    fun putVariable(variable: String?) = setVariable(variable)
    fun getVariable(): String = CacheManager.get("sourceVariable_" + getKey()) ?: ""
    fun putVariable(key: String, value: String): String = put(key, value)
    fun getVariable(key: String): String = get(key)

    fun put(key: String, value: String): String { CacheManager.put("v_" + getKey() + "_" + key, value); return value }
    fun get(key: String): String = CacheManager.get("v_" + getKey() + "_" + key) ?: ""

    /**
     * 设置并发率
     * 对应 Legado BaseSource.putConcurrent()
     */
    fun putConcurrent(value: String) {
        concurrentRate = value
        CacheManager.put("concurrentRate_" + getKey(), value)
    }

    /**
     * 获取并发率（优先使用运行时设置，其次使用书源定义）
     */
    fun getEffectiveConcurrentRate(): String? {
        return CacheManager.get("concurrentRate_" + getKey()) ?: concurrentRate
    }

    /**
     * 执行JS，自动注入 jsLib 共享作用域
     * 对应 Legado BaseSource.evalJS()，集成 SharedJsScope.getShareScope()
     */
    /**
     * 刷新发现页 - 对应 Legado BaseSource.refreshExplore()
     */
    fun refreshExplore() {
        // LNR 插件中由宿主控制发现页刷新
        Debug.log("refreshExplore called for ")
    }

    /**
     * 刷新 JSLib 缓存 - 对应 Legado BaseSource.refreshJSLib()
     */
    fun refreshJSLib() {
        SharedJsScope.remove(jsLib)
    }
    fun evalJS(jsStr: String, bindingsConfig: (ScriptBindings) -> Unit = {}): Any? {
        val bindings = buildScriptBindings { b ->
            b["java"] = this
            b["source"] = this
            b["baseUrl"] = getKey()
            b["cookie"] = CookieStore
            b["cache"] = CacheManager
            bindingsConfig(b)
        }
        val sharedScope = SharedJsScope.getScope(jsLib)
        val scope = if (sharedScope != null) {
            bindings.apply { prototype = sharedScope }
            RhinoScriptEngine.getRuntimeScope(bindings)
        } else {
            RhinoScriptEngine.getRuntimeScope(bindings)
        }
        return RhinoScriptEngine.eval(jsStr, scope)
    }
}
