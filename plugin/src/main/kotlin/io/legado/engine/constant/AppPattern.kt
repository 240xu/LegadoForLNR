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

    /** 提取 <js>...</js> 或 <webJs>...</webJs> 中的JS代码 */
    fun unwrapJsTag(str: String, tag: String = "js"): String {
        val regex = Regex("<$tag>([\\s\\S]*?)</$tag>", RegexOption.IGNORE_CASE)
        return regex.find(str)?.groupValues?.getOrNull(1) ?: str
    }

    /** 去掉 @js: 或 <js></js> 前缀，返回纯JS代码 */
    fun stripJsPrefix(str: String): String {
        return when {
            str.startsWith("@js:", true) -> str.substring(4)
            str.startsWith("<js>", true) -> unwrapJsTag(str, "js")
            else -> str
        }
    }
}
