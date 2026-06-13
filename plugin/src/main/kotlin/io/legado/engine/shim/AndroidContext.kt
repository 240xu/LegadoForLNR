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
}
