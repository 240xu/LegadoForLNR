package io.legado.engine.rule

import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import io.legado.engine.shim.GSON
import java.io.StringReader

object UrlOptionParser {

    fun split(raw: String): Pair<String, String?> {
        val text = raw.trim()
        for (index in text.length - 1 downTo 0) {
            if (text[index] != ',') continue
            val option = text.substring(index + 1).trim()
            if (!option.startsWith("{")) continue
            if (!isBalancedObject(option)) continue
            return text.substring(0, index).trim() to option
        }
        return text to null
    }

    fun strip(raw: String): String = split(raw).first

    fun parseMap(jsonLike: String?): Map<String, Any?>? {
        if (jsonLike.isNullOrBlank()) return null
        return try {
            val reader = JsonReader(StringReader(jsonLike.trim()))
            reader.isLenient = true
            GSON.fromJson<Map<String, Any?>>(reader, object : TypeToken<Map<String, Any?>>() {}.type)
        } catch (_: Exception) {
            null
        }
    }

    fun parseHeaders(value: Any?): Map<String, String> {
        return when (value) {
            is Map<*, *> -> value.entries.asSequence().associateNotNull { (key, v) ->
                val name = key?.toString()?.takeIf { it.isNotBlank() } ?: return@associateNotNull null
                name to (v?.toString() ?: "")
            }
            is String -> parseMap(value)?.let(::parseHeaders) ?: parseHeaderLines(value)
            else -> emptyMap()
        }
    }

    fun parseHeaderLines(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val index = line.indexOf(':').takeIf { it > 0 } ?: line.indexOf('=').takeIf { it > 0 }
                index?.let { line.substring(0, it).trim() to line.substring(it + 1).trim() }
            }
            .toMap()
    }

    private fun isBalancedObject(text: String): Boolean {
        var quote: Char? = null
        var escape = false
        var depth = 0
        for ((i, ch) in text.withIndex()) {
            if (escape) {
                escape = false
                continue
            }
            if (ch == '\\') {
                escape = true
                continue
            }
            if (quote != null) {
                if (ch == quote) quote = null
                continue
            }
            when (ch) {
                '"', '\'' -> quote = ch
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0 && i != text.lastIndex) return false
                    if (depth < 0) return false
                }
            }
        }
        return depth == 0 && quote == null
    }

    private inline fun <K, V> Sequence<Map.Entry<K, V>>.associateNotNull(
        transform: (Map.Entry<K, V>) -> Pair<String, String>?
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (entry in this) {
            val pair = transform(entry) ?: continue
            result[pair.first] = pair.second
        }
        return result
    }
}
