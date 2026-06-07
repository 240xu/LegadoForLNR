package io.legado.app.help.rhino

import io.legado.engine.data.BaseSource

/**
 * Native JS wrapper for BaseSource - allows source.xxx() calls from JS
 * Ported from Legado's NativeBaseSource
 */
class NativeBaseSource(private val source: BaseSource) {
    fun getTag(): String = source.getTag()
    fun getKey(): String = source.getKey()
    fun getVariable(): String = source.getVariable()
    fun putVariable(variable: String?) = source.putVariable(variable)
    fun getLoginInfo(): String? = source.getLoginInfo()
    fun putLoginInfo(info: String): Boolean = source.putLoginInfo(info)
    fun removeLoginInfo() = source.removeLoginInfo()
    fun getLoginHeader(): String? = source.getLoginHeader()
    fun putLoginHeader(header: String) = source.putLoginHeader(header)
    fun put(key: String, value: String): String = source.put(key, value)
    fun get(key: String): String = source.get(key)
}

