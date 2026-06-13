package io.legado.engine.constant

import android.util.Log

/**
 * App logging - ported from Legado
 */
object AppLog {
    fun put(msg: String, e: Throwable? = null) {
        Log.e("LegadoEngine", msg, e)
    }
}
