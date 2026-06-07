package io.legado.engine.shim

import android.content.Context

/**
 * Android Context holder - replaces splitties.init.appCtx
 * Must be initialized before any engine code runs.
 *
 * 集中持有 Context 和平台特定查询方法，
 * engine 核心逻辑通过此 shim 间接访问 Android API。
 */
object AndroidContext {
    lateinit var appCtx: Context
        private set

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    fun isInitialized(): Boolean = ::appCtx.isInitialized

    /**
     * 获取 Android ID — 集中在此 shim 中，engine 核心不直接引用 android.provider.*
     */
    fun androidId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                appCtx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }
}
