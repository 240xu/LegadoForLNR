package io.legado.engine.constant

import java.util.regex.Pattern

/**
 * App patterns - ported from Legado
 */
object AppPattern {
    val JS_PATTERN: Pattern = Pattern.compile("<js>([\\s\\S]*?)</js>|@js:([\\s\\S]*)", Pattern.CASE_INSENSITIVE)
    val WebJS_PATTERN: Pattern = Pattern.compile("<webJs>([\\s\\S]*?)</webJs>|@webjs:([\\s\\S]{5,})", Pattern.CASE_INSENSITIVE)
    val titleNumPattern: Pattern = Pattern.compile("^(.*?)(\\d+)(.*)$")

    val EXPLORE_URL_PATTERN = Pattern.compile("(.+)::\\{(.+)\\}")

    fun isJs(rule: String): Boolean = rule.startsWith("@js:") || rule.startsWith("<js>")
    fun isWebJs(rule: String): Boolean = rule.startsWith("@webJs:") || rule.startsWith("<webJs>")
}
