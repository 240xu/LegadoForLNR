package io.legado.engine.shim

import android.content.Context
import android.content.SharedPreferences
import io.legado.engine.spi.CacheProvider
import io.legado.engine.rule.QueryTTF

/**
 * CacheManager — 向后兼容的门面，内部委托 CacheProvider。
 * 如果宿主注入了自定义 CacheProvider 则使用宿主实现，
 * 否则回退到 SharedPreferences（插件进程直连场景）。
 */
object CacheManager : CacheProvider {
    private const val PREFS_NAME = "legado_cache"

    private val memoryCache = object : LinkedHashMap<String, Any>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>?): Boolean = size > 512
    }
    private val queryTTFCache = object : LinkedHashMap<String, QueryTTF>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, QueryTTF>?): Boolean = size > 4
    }

    /** 宿主注入的实现；为 null 时回退 SharedPreferences */
    @Volatile
    var delegate: CacheProvider? = null

    private fun prefs(): SharedPreferences =
        AndroidContext.appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---- CacheProvider 接口实现（委托或回退） ----

    override fun getString(key: String): String? =
        delegate?.getString(key) ?: prefs().getString(key, null)

    // 向后兼容旧签名
    fun get(key: String): String? = getString(key)

    override fun putString(key: String, value: String) {
        putMemory(key, value)
        (delegate ?: return run { prefs().edit().putString(key, value).apply() }).putString(key, value)
    }

    // 向后兼容旧签名
    fun put(key: String, value: String) = putString(key, value)

    override fun putString(key: String, value: String, saveTimeSeconds: Int) {
        putMemory(key, value)
        delegate?.putString(key, value, saveTimeSeconds)
            ?: prefs().edit().putString(key, value).apply().also {
                if (saveTimeSeconds > 0) {
                    prefs().edit().putLong(key + "_expire", System.currentTimeMillis() + saveTimeSeconds * 1000L).apply()
                }
            }
    }

    // 向后兼容旧签名
    fun put(key: String, value: String, saveTime: Int) = putString(key, value, saveTime)

    override fun delete(key: String) {
        deleteMemory(key)
        delegate?.delete(key)
            ?: prefs().edit().remove(key).remove(key + "_expire").apply()
    }

    override fun deleteByPrefix(prefix: String) {
        delegate?.deleteByPrefix(prefix) ?: run {
            val editor = prefs().edit()
            prefs().all.keys.filter { it.startsWith(prefix) }.forEach {
                editor.remove(it); editor.remove(it + "_expire"); deleteMemory(it)
            }
            editor.apply()
        }
    }

    override fun contains(key: String): Boolean {
        return delegate?.contains(key) ?: run {
            val expire = prefs().getLong(key + "_expire", 0)
            if (expire > 0 && System.currentTimeMillis() > expire) { delete(key); return false }
            prefs().contains(key)
        }
    }

    override fun getLong(key: String): Long = delegate?.getLong(key) ?: prefs().getLong(key, 0)
    override fun putLong(key: String, value: Long) { delegate?.putLong(key, value) ?: prefs().edit().putLong(key, value).apply() }
    override fun getInt(key: String): Int = delegate?.getInt(key) ?: prefs().getInt(key, 0)
    override fun putInt(key: String, value: Int) { delegate?.putInt(key, value) ?: prefs().edit().putInt(key, value).apply() }
    override fun getFloat(key: String): Float = delegate?.getFloat(key) ?: prefs().getFloat(key, 0f)
    override fun putFloat(key: String, value: Float) { delegate?.putFloat(key, value) ?: prefs().edit().putFloat(key, value).apply() }
    override fun getBoolean(key: String): Boolean = delegate?.getBoolean(key) ?: prefs().getBoolean(key, false)
    override fun putBoolean(key: String, value: Boolean) { delegate?.putBoolean(key, value) ?: prefs().edit().putBoolean(key, value).apply() }

    // ---- 内存缓存（QueryTTF 等热数据） ----

    @Synchronized fun putMemory(key: String, value: Any?) {
        if (value == null) memoryCache.remove(key) else memoryCache[key] = value
    }
    @Synchronized fun getFromMemory(key: String): Any? = memoryCache[key]
    @Synchronized fun deleteMemory(key: String) { memoryCache.remove(key) }
    @Synchronized fun getQueryTTF(key: String): QueryTTF? = queryTTFCache[key]
    @Synchronized fun putQueryTTF(key: String, queryTTF: QueryTTF) { queryTTFCache[key] = queryTTF }
}
