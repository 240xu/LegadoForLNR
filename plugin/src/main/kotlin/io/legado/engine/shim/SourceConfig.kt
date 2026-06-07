package io.legado.engine.shim

import android.content.Context
import io.legado.engine.spi.SourceConfigProvider

/**
 * SourceConfig — 向后兼容门面，委托 SourceConfigProvider。
 */
object SourceConfig : SourceConfigProvider {
    private const val PREFS_NAME = "legado_source_config"

    @Volatile
    var delegate: SourceConfigProvider? = null

    private fun prefs() = AndroidContext.appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun get(sourceKey: String, configKey: String): String? =
        delegate?.get(sourceKey, configKey) ?: prefs().getString(sourceKey + "::" + configKey, null)

    override fun put(sourceKey: String, configKey: String, value: String) {
        delegate?.put(sourceKey, configKey, value)
            ?: prefs().edit().putString(sourceKey + "::" + configKey, value).apply()
    }

    override fun delete(sourceKey: String, configKey: String) {
        delegate?.delete(sourceKey, configKey)
            ?: prefs().edit().remove(sourceKey + "::" + configKey).apply()
    }

    override fun clear(sourceKey: String) {
        delegate?.clear(sourceKey) ?: run {
            val prefix = sourceKey + "::"
            val editor = prefs().edit()
            prefs().all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
            editor.apply()
        }
    }

    override fun getAll(sourceKey: String): Map<String, String> {
        delegate?.getAll(sourceKey)?.let { return it }
        val prefix = sourceKey + "::"
        val result = mutableMapOf<String, String>()
        prefs().all.forEach { (k, v) -> if (k.startsWith(prefix) && v is String) result[k.removePrefix(prefix)] = v }
        return result
    }
}
