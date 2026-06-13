package io.legado.engine.shim

import android.content.Context

object SourceConfig {
    private const val PREFS_NAME = "legado_source_config"

    private fun prefs() = AndroidContext.appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(sourceKey: String, configKey: String): String? {
        return prefs().getString(sourceKey + "::" + configKey, null)
    }

    fun put(sourceKey: String, configKey: String, value: String) {
        prefs().edit().putString(sourceKey + "::" + configKey, value).apply()
    }

    fun delete(sourceKey: String, configKey: String) {
        prefs().edit().remove(sourceKey + "::" + configKey).apply()
    }

    fun clear(sourceKey: String) {
        val prefix = sourceKey + "::"
        val editor = prefs().edit()
        prefs().all.keys
            .filter { it.startsWith(prefix) }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun getAll(sourceKey: String): Map<String, String> {
        val prefix = sourceKey + "::"
        val result = mutableMapOf<String, String>()
        prefs().all.forEach { (k, v) ->
            if (k.startsWith(prefix) && v is String) {
                result[k.removePrefix(prefix)] = v
            }
        }
        return result
    }
}
