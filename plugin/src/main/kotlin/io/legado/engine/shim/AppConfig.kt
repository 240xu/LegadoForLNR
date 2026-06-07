package io.legado.engine.shim

import android.content.Context
import android.webkit.WebSettings
import io.legado.engine.spi.AppConfigProvider

/**
 * AppConfig — 向后兼容门面，委托 AppConfigProvider。
 */
object AppConfig : AppConfigProvider {
    @Volatile
    var delegate: AppConfigProvider? = null

    private val _userAgent: String by lazy {
        delegate?.userAgent ?: try {
            WebSettings.getDefaultUserAgent(AndroidContext.appCtx)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
    }

    override val userAgent: String get() = delegate?.userAgent ?: _userAgent
    override val threadCount: Int get() = delegate?.threadCount ?: 6
}
