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

    val rnRegex = Regex("\r\n|\r|\n")
    val wordCountRegex = Regex("(?:^|[\u5B57\u6570\u3010\u3011\uFF0C\u3001]|\\s+)([0-9\u4E07\u5343\u767E\u5341\\.]{1,6}\u5B57)")

    fun isJs(rule: String): Boolean = rule.startsWith("@js:") || rule.startsWith("<js>")
    fun isWebJs(rule: String): Boolean = rule.startsWith("@webJs:") || rule.startsWith("<webJs>")
}
