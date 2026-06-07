package io.legado.engine.spi

/**
 * 应用配置 SPI —— engine 需要的运行时配置。
 */
interface AppConfigProvider {
    val userAgent: String
    val threadCount: Int
}
