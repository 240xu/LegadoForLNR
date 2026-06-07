package io.legado.engine.spi

/**
 * 缓存持久化 SPI —— engine 模块只依赖此接口，宿主提供实现。
 * 宿主实现可以使用 SharedPreferences / DataStore / Room 等任意方案。
 */
interface CacheProvider {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun putString(key: String, value: String, saveTimeSeconds: Int)
    fun delete(key: String)
    fun deleteByPrefix(prefix: String)
    fun contains(key: String): Boolean

    fun getLong(key: String): Long
    fun putLong(key: String, value: Long)
    fun getInt(key: String): Int
    fun putInt(key: String, value: Int)
    fun getFloat(key: String): Float
    fun putFloat(key: String, value: Float)
    fun getBoolean(key: String): Boolean
    fun putBoolean(key: String, value: Boolean)
}
