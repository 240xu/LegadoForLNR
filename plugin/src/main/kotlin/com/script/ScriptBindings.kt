package com.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject

/**
 * 与 Legado 一致：extends NativeObject，可直接作为 Rhino scope 使用
 */
class ScriptBindings : NativeObject() {

    companion object {
        private val topLevelScope: ScriptableObject by lazy {
            val cx = Context.enter()
            try {
                cx.initStandardObjects()
            } finally {
                Context.exit()
            }
        }
    }

    init {
        prototype = topLevelScope
    }

    operator fun set(key: String, value: Any?) {
        val cx = Context.enter()
        try {
            put(key, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    operator fun get(key: String): Any? {
        return super.get(key, this)
    }

    fun entries(): Set<Map.Entry<String, Any?>> {
        return keys.map { key ->
            val k = key.toString()
            object : Map.Entry<String, Any?> {
                override val key = k
                override val value = get(k, this@ScriptBindings)
            }
        }.toSet()
    }
}

fun buildScriptBindings(config: (ScriptBindings) -> Unit): ScriptBindings {
    val b = ScriptBindings()
    config(b)
    return b
}
