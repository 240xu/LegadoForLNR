package io.legado.engine.spi

import android.content.Context

/**
 * 引擎初始化入口 —— 插件加载时调用 Engine.init() 注入所有 SPI 实现。
 * engine 模块内部只通过此 object 获取依赖，不再直接 import android.*
 */
object Engine {
    private var _cache: CacheProvider? = null
    private var _sourceConfig: SourceConfigProvider? = null
    private var _appConfig: AppConfigProvider? = null
    private var _log: LogProvider? = null
    private var _context: Context? = null

    val cache: CacheProvider
        get() = _cache ?: error("Engine not initialized: CacheProvider missing")
    val sourceConfig: SourceConfigProvider
        get() = _sourceConfig ?: error("Engine not initialized: SourceConfigProvider missing")
    val appConfig: AppConfigProvider
        get() = _appConfig ?: error("Engine not initialized: AppConfigProvider missing")
    val log: LogProvider
        get() = _log ?: error("Engine not initialized: LogProvider missing")

    /**
     * Android Context —— 仅 WebView 等必须依赖 Android 的组件使用。
     * engine 核心解析逻辑不应调用此属性。
     */
    val context: Context
        get() = _context ?: error("Engine not initialized: Context missing")

    val isInitialized: Boolean get() = _cache != null

    fun init(
        context: Context,
        cache: CacheProvider,
        sourceConfig: SourceConfigProvider,
        appConfig: AppConfigProvider,
        log: LogProvider
    ) {
        _context = context.applicationContext
        _cache = cache
        _sourceConfig = sourceConfig
        _appConfig = appConfig
        _log = log
    }
}
