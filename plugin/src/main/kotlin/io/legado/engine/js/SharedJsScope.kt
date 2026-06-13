package io.legado.engine.js

import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.engine.http.HttpClient
import io.legado.engine.shim.Debug
import io.legado.engine.shim.GSON
import io.legado.engine.shim.fromJsonObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest

/**
 * 共享JS作用域管理器
 * 对应 Legado 的 SharedJsScope，负责 jsLib 注入
 * 支持两种格式：
 * 1. 直接 JavaScript 代码片段
 * 2. {"example":"https://www.example.com/js/example.js", ...} JSON对象，自动下载并缓存
 */
object SharedJsScope {

    private val scopeMap = object : java.util.LinkedHashMap<String, Scriptable>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Scriptable>?): Boolean = size > 16
    }
    private val jsFileCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getScope(jsLib: String?): Scriptable? {
        if (jsLib.isNullOrBlank()) return null
        val key = md5(jsLib)
        var scope = synchronized(scopeMap) { scopeMap[key] }
        if (scope == null) {
            scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
            try {
                if (jsLib.trimStart().startsWith("{")) {
                    // JSON格式: {"name":"url", ...}
                    val jsMap: Map<String, String>? = GSON.fromJsonObject<Map<String, String>>(jsLib)
                    jsMap?.values?.forEach { value ->
                        if (value.startsWith("http://") || value.startsWith("https://")) {
                            val js = downloadJs(value)
                            if (js != null) {
                                RhinoScriptEngine.eval(js, scope)
                            } else {
                                Debug.log("jsLib download failed: $value")
                            }
                        }
                    }
                } else {
                    // 直接JS代码
                    RhinoScriptEngine.eval(jsLib, scope)
                }
                // 阻止新全局变量创建（函数内未用var的隐性全局变量）
                if (scope is ScriptableObject) {
                    scope.preventExtensions()
                }
            } catch (e: Exception) {
                Debug.log("jsLib eval error: ${e.message}")
            }
            synchronized(scopeMap) { scopeMap[key] = scope }
        }
        return scope
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) return
        val key = md5(jsLib)
        synchronized(scopeMap) { scopeMap.remove(key) }
        // 如果是JSON格式，清除下载缓存
        if (jsLib.trimStart().startsWith("{")) {
            val jsMap: Map<String, String>? = try { GSON.fromJsonObject(jsLib) } catch (_: Exception) { null }
            jsMap?.values?.forEach { value ->
                jsFileCache.remove(md5(value))
            }
        }
    }

    private fun downloadJs(url: String): String? {
        val fileName = md5(url)
        jsFileCache[fileName]?.let { return it }
        return try {
            val body = HttpClient.get(url).body
            if (body.isNotBlank()) {
                jsFileCache[fileName] = body
                body
            } else null
        } catch (e: Exception) {
            Debug.log("jsLib download error: ${e.message}")
            null
        }
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}