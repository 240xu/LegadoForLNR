package io.legado.engine.spi

/**
 * 日志输出 SPI。
 * 宿主可对接 Android Log / 自定义日志系统 / 文件日志等。
 */
interface LogProvider {
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
    fun putDebug(msg: String, throwable: Throwable? = null)
}
