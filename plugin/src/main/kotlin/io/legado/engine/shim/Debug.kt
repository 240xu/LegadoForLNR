package io.legado.engine.shim

import io.legado.engine.spi.LogProvider

/**
 * Debug — 向后兼容门面，委托 LogProvider。
 */
object Debug : LogProvider {
    /** 向后兼容旧回调 */
    var logger: ((String, String?) -> Unit)? = null

    @Volatile
    var delegate: LogProvider? = null

    override fun d(tag: String, msg: String) {
        delegate?.d(tag, msg) ?: android.util.Log.d(tag, msg)
    }

    override fun i(tag: String, msg: String) {
        delegate?.i(tag, msg) ?: android.util.Log.i(tag, msg)
    }

    override fun w(tag: String, msg: String) {
        delegate?.w(tag, msg) ?: android.util.Log.w(tag, msg)
    }

    override fun e(tag: String, msg: String, throwable: Throwable?) {
        delegate?.e(tag, msg, throwable) ?: android.util.Log.e(tag, msg, throwable)
    }

    override fun putDebug(msg: String, throwable: Throwable?) {
        delegate?.putDebug(msg, throwable) ?: logger?.invoke(msg, null) ?: android.util.Log.d("LegadoDebug", msg)
    }

    // 向后兼容旧 API
    fun log(sourceUrl: String, msg: String) {
        logger?.invoke(msg, sourceUrl) ?: delegate?.d("LegadoDebug", "[$sourceUrl] $msg")
            ?: android.util.Log.d("LegadoDebug", "[$sourceUrl] $msg")
    }

    fun log(msg: String) {
        logger?.invoke(msg, null) ?: delegate?.d("LegadoDebug", msg)
            ?: android.util.Log.d("LegadoDebug", msg)
    }
}
