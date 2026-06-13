package com.script
class ScriptBindings {
    private val map = mutableMapOf<String, Any?>()
    var prototype: org.mozilla.javascript.Scriptable? = null
    operator fun set(key: String, value: Any?) { map[key] = value }
    operator fun get(key: String): Any? = map[key]
    fun entries(): Set<Map.Entry<String, Any?>> = map.entries
}
fun buildScriptBindings(config: (ScriptBindings) -> Unit): ScriptBindings {
    val b = ScriptBindings(); config(b); return b
}
