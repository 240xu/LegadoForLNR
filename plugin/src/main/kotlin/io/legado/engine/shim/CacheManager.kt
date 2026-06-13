package io.legado.engine.shim

import android.content.Context
import android.content.SharedPreferences
import io.legado.engine.rule.QueryTTF

import java.util.concurrent.ConcurrentHashMap

object CacheManager {
    private const val PREFS_NAME = "legado_cache"
    private val memoryCache = object : ConcurrentHashMap<String, Any>(128) {
        // LRU eviction not needed for ConcurrentHashMap - GC handles cleanup
    }
    private val queryTTFCache = ConcurrentHashMap<String, QueryTTF>(8)

    private fun prefs(): SharedPreferences {
        return AndroidContext.appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun get(key: String): String? = prefs().getString(key, null)

    fun put(key: String, value: String) {
        putMemory(key, value)
        prefs().edit().putString(key, value).apply()
    }

    fun put(key: String, value: String, saveTime: Int) {
        putMemory(key, value)
        prefs().edit().putString(key, value).apply()
        if (saveTime > 0) {
            prefs().edit().putLong(key + "_expire", System.currentTimeMillis() + saveTime * 1000L).apply()
        }
    }

    fun delete(key: String) {
        deleteMemory(key)
        prefs().edit().remove(key).remove(key + "_expire").apply()
    }

    fun deleteByPrefix(prefix: String) {
        val editor = prefs().edit()
        prefs().all.keys
            .filter { it.startsWith(prefix) }
            .forEach {
                editor.remove(it)
                editor.remove(it + "_expire")
                deleteMemory(it)
            }
        editor.apply()
    }

    fun contains(key: String): Boolean {
        val expire = prefs().getLong(key + "_expire", 0)
        if (expire > 0 && System.currentTimeMillis() > expire) {
            delete(key)
            return false
        }
        return prefs().contains(key)
    }

    fun getLong(key: String): Long = prefs().getLong(key, 0)
    fun putLong(key: String, value: Long) = prefs().edit().putLong(key, value).apply()
    fun getInt(key: String): Int = prefs().getInt(key, 0)
    fun putInt(key: String, value: Int) = prefs().edit().putInt(key, value).apply()
    fun getFloat(key: String): Float = prefs().getFloat(key, 0f)
    fun putFloat(key: String, value: Float) = prefs().edit().putFloat(key, value).apply()
    fun getBoolean(key: String): Boolean = prefs().getBoolean(key, false)
    fun putBoolean(key: String, value: Boolean) = prefs().edit().putBoolean(key, value).apply()

    fun putMemory(key: String, value: Any?) {
        if (value == null) memoryCache.remove(key) else memoryCache[key] = value
    }

    fun getFromMemory(key: String): Any? = memoryCache[key]

    fun deleteMemory(key: String) {
        memoryCache.remove(key)
    }

    fun getQueryTTF(key: String): QueryTTF? = queryTTFCache[key]

    fun putQueryTTF(key: String, queryTTF: QueryTTF) {
        queryTTFCache[key] = queryTTF
    }
}
