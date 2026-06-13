package com.script.rhino
import com.script.ScriptBindings
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper
import java.util.concurrent.ConcurrentHashMap
object RhinoScriptEngine {
    private val compiledCache = ConcurrentHashMap<String, CompiledScript>()
    fun getRuntimeScope(bindings: ScriptBindings): Scriptable {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.setClassShutter(RhinoClassShutter)
            bindings.prototype = cx.initStandardObjects()
            bindings
        } finally { Context.exit() }
    }
    fun eval(jsStr: String, scope: Scriptable): Any? {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.setClassShutter(RhinoClassShutter)
            val result = cx.evaluateString(scope, jsStr, "legado_js", 1, null)
            unwrap(result)
        } finally { Context.exit() }
    }
    fun eval(jsStr: String, bindings: ScriptBindings): Any? {
        val scope = getRuntimeScope(bindings)
        return eval(jsStr, scope)
    }
    fun compile(jsStr: String): CompiledScript {
        return compiledCache.getOrPut(jsStr) {
            val cx = Context.enter()
            try {
                cx.optimizationLevel = -1
                cx.setClassShutter(RhinoClassShutter)
                val scope = cx.initStandardObjects()
                val script = cx.compileString(jsStr, "legado_js", 1, null)
                CompiledScript(script, scope)
            } finally { Context.exit() }
        }
    }
    private fun unwrap(result: Any?): Any? {
        return when (result) {
            is Undefined -> null
            null -> null
            is NativeArray -> result.toJavaList()
            is NativeObject -> result.toJavaMap()
            else -> Context.jsToJava(result, Any::class.java)
        }
    }

    private fun unwrapJsValue(value: Any?): Any? {
        return when (value) {
            null, is Undefined -> null
            is NativeArray -> value.toJavaList()
            is NativeObject -> value.toJavaMap()
            is Wrapper -> value.unwrap()
            else -> Context.jsToJava(value, Any::class.java)
        }
    }

    private fun NativeArray.toJavaList(): List<Any?> {
        val max = length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return (0 until max).map { index ->
            unwrapJsValue(get(index, this))
        }
    }

    private fun NativeObject.toJavaMap(): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        ids.forEach { id ->
            val key = id.toString()
            val value = when (id) {
                is Number -> get(id.toInt(), this)
                else -> get(key, this)
            }
            if (value !is Undefined) map[key] = unwrapJsValue(value)
        }
        return map
    }
}
class CompiledScript(private val script: org.mozilla.javascript.Script, private val baseScope: Scriptable) {
    fun eval(scope: Scriptable? = null): Any? {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            cx.setClassShutter(RhinoClassShutter)
            val result = script.exec(cx, scope ?: baseScope)
            when (result) {
                is org.mozilla.javascript.Undefined -> null
                else -> Context.jsToJava(result, Any::class.java)
            }
        } finally { Context.exit() }
    }
}

