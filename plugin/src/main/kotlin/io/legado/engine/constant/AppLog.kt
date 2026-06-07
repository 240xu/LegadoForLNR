package io.legado.engine.constant

import io.legado.engine.shim.Debug

/**
 * AppLog — 委托 Debug（LogProvider 门面）。
 * 保留 android.util.Log 作为最后回退仅在 Debug shim 中。
 */
object AppLog {
    fun put(msg: String, e: Throwable? = null) {
        Debug.e("LegadoEngine", msg, e)
    }

    fun putDebug(msg: String, e: Throwable? = null) {
        Debug.putDebug(msg, e)
    }
}
