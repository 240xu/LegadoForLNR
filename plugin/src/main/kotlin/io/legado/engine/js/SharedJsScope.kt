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

    private val scopeMap = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<Scriptable>>()
    private val jsFileCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getScope(jsLib: String?): Scriptable? {
        if (jsLib.isNullOrBlank()) return null
        val key = md5(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope == null) {
            scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
            try {
                resolveScripts(jsLib).forEach { js ->
                    RhinoScriptEngine.eval(js, scope)
                }
                // 阻止新全局变量创建（函数内未用var的隐性全局变量）
                if (scope is ScriptableObject) {
                    scope.preventExtensions()
                }
            } catch (e: Exception) {
                Debug.log("jsLib eval error: ${e.message}")
            }
            scopeMap[key] = java.lang.ref.WeakReference(scope)
        }
        return scope
    }

    /**
     * 将 jsLib 的共享全局变量补到本次脚本的当前作用域。
     *
     * 大多数规则可以通过 shared scope 的 prototype 读取 jsLib；loginUi 的按钮/输入动作还会
     * 混入 loginUrl 中的函数、result 表单数据和 java 回调。这里不重复执行 jsLib，而是从
     * 已缓存的共享 scope 复制可枚举的全局函数/变量，避免同名 const/let 重复声明。
     */
    fun evalInto(jsLib: String?, scope: Scriptable): Boolean {
        val sharedScope = getScope(jsLib) ?: return false
        var injected = false
        sharedScope.ids.forEach { id ->
            try {
                when (id) {
                    is String -> {
                        if (scope.has(id, scope)) return@forEach
                        val value = ScriptableObject.getProperty(sharedScope, id)
                        if (value != Scriptable.NOT_FOUND) {
                            ScriptableObject.putProperty(scope, id, value)
                            injected = true
                        }
                    }
                    is Number -> {
                        val index = id.toInt()
                        if (scope.has(index, scope)) return@forEach
                        val value = ScriptableObject.getProperty(sharedScope, index)
                        if (value != Scriptable.NOT_FOUND) {
                            ScriptableObject.putProperty(scope, index, value)
                            injected = true
                        }
                    }
                }
            } catch (e: Exception) {
                Debug.log("jsLib evalInto copy error: ${e.message}")
            }
        }
        return injected
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) return
        val key = md5(jsLib)
        scopeMap.remove(key)
        // 如果是JSON格式，清除下载缓存
        if (jsLib.trimStart().startsWith("{")) {
            val jsMap: Map<String, String>? = try { GSON.fromJsonObject(jsLib) } catch (_: Exception) { null }
            jsMap?.values?.forEach { value ->
                jsFileCache.remove(md5(value))
            }
        }
    }

    private fun resolveScripts(jsLib: String): List<String> {
        if (jsLib.trimStart().startsWith("{")) {
            val jsMap: Map<String, String>? = try {
                GSON.fromJsonObject<Map<String, String>>(jsLib)
            } catch (_: Exception) {
                null
            }
            return jsMap?.values?.mapNotNull { value ->
                if (value.startsWith("http://") || value.startsWith("https://")) {
                    downloadJs(value) ?: run {
                        Debug.log("jsLib download failed: $value")
                        null
                    }
                } else {
                    null
                }
            }.orEmpty()
        }
        return listOf(jsLib)
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
