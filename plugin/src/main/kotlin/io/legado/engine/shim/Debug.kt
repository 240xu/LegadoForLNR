package io.legado.engine.shim

/**
 * Debug shim - replaces Legado's Debug
 */
object Debug {
    var logger: ((String, String?) -> Unit)? = null

    fun log(sourceUrl: String, msg: String) {
        logger?.invoke(msg, sourceUrl) ?: android.util.Log.d("LegadoDebug", "[$sourceUrl] $msg")
    }

    fun log(msg: String) {
        logger?.invoke(msg, null) ?: android.util.Log.d("LegadoDebug", msg)
    }
}
