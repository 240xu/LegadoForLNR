package io.legado.engine.shim

import android.content.Context

/**
 * Android Context holder - replaces splitties.init.appCtx
 * Must be initialized before any engine code runs
 */
object AndroidContext {
    lateinit var appCtx: Context
        private set

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    fun isInitialized(): Boolean = ::appCtx.isInitialized

    fun getAndroidId(): String {
        return try {
            android.provider.Settings.Secure.getString(
                appCtx.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "0000000000000000"
        } catch (_: Exception) { "0000000000000000" }
    }
}
