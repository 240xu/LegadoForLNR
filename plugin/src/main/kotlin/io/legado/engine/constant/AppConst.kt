package io.legado.engine.constant

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * App constants - ported from Legado
 */
object AppConst {
    const val APP_TAG = "LegadoLNR"
    const val UA_NAME = "User-Agent"
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    const val ANDROID_ID = "legado_lnr_plugin"

    val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val dateFormatGMT: SimpleDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)

    const val SCHEME_HTTP = "http"
    const val SCHEME_HTTPS = "https"
    const val SCHEME_JAR = "jar"
    const val SCHEME_FILE = "file"
    const val SCHEME_CONTENT = "content"
    const val SCHEME_EPUB = "epub"
    const val SCHEME_FONT = "font"
}
