package io.legado.engine.shim

import android.content.Context
import android.webkit.WebSettings

/**
 * AppConfig shim - replaces Legado's AppConfig
 */
object AppConfig {
    private val _userAgent: String by lazy {
        try {
            WebSettings.getDefaultUserAgent(AndroidContext.appCtx)
        } catch (_: Exception) {
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }
    }

    val userAgent: String get() = _userAgent
    var threadCount: Int = 6
}
