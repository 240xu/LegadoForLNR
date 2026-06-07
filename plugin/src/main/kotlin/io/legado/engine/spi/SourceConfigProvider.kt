package io.legado.engine.spi

/**
 * 源配置持久化 SPI —— 按 sourceKey::configKey 隔离存储。
 */
interface SourceConfigProvider {
    fun get(sourceKey: String, configKey: String): String?
    fun put(sourceKey: String, configKey: String, value: String)
    fun delete(sourceKey: String, configKey: String)
    fun clear(sourceKey: String)
    fun getAll(sourceKey: String): Map<String, String>
}
