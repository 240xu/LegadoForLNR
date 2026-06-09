package io.legado.engine.http

import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * 简易运行时 Cookie 存储。
 *
 * 1. 接收 Set-Cookie 时只提取 name=value 部分，丢弃 Path / HttpOnly / Secure 等属性。
 * 2. 同一域下相同 cookie name 用新值替换，而非无限追加。
 * 3. replaceCookie() 为全量替换，setCookie() 为合并追加。
 */
object CookieStore {
    // domain -> (cookieName -> cookieValue)
    private val store: ConcurrentMap<String, ConcurrentHashMap<String, String>> =
        ConcurrentHashMap()

    /**
     * 从 Set-Cookie header 提取 name=value（丢弃属性部分），合并到域对应的 map 中。
     * 一次 Set-Cookie 只含一个 cookie 对；若传入的是已合并的 Cookie header
     * （多对以分号分隔），也会按分号拆分逐个合并。
     */
    fun setCookie(domain: String, cookie: String) {
        if (cookie.isBlank()) return
        val d = normalizeDomain(domain)
        val map = store.getOrPut(d) { ConcurrentHashMap() }
        parseSetCookie(cookie).forEach { (name, value) -> map[name] = value }
    }

    fun setCookieFromUrl(url: String, cookie: String) {
        val domain = urlToHost(url) ?: return
        setCookie(domain, cookie)
    }

    fun getCookie(domain: String): String {
        val map = store[normalizeDomain(domain)] ?: return ""
        if (map.isEmpty()) return ""
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    fun getCookieHeader(url: String): Map<String, String> {
        val domain = urlToHost(url) ?: return emptyMap()
        val cookie = getCookie(domain)
        return if (cookie.isBlank()) emptyMap() else mapOf("Cookie" to cookie)
    }

    fun clear(domain: String) { store.remove(normalizeDomain(domain)) }

    fun clearAll() { store.clear() }

    /**
     * 全量替换：先清空域，再把传入 cookie 的 name=value 合并进去。
     */
    fun replaceCookie(key: String, cookie: String) {
        val d = normalizeDomain(urlToHost(key) ?: key)
        val map = store.getOrPut(d) { ConcurrentHashMap() }
        map.clear()
        parseCookieHeader(cookie).forEach { (name, value) -> map[name] = value }
    }

    fun removeCookie(key: String) {
        val d = normalizeDomain(urlToHost(key) ?: key)
        store.remove(d)
    }

    fun getKey(tag: String, key: String): String {
        val domain = normalizeDomain(urlToHost(tag) ?: tag)
        return store[domain]?.get(key) ?: ""
    }

    // ---- helpers ----

    private fun urlToHost(url: String): String? = try { URL(url).host } catch (_: Exception) { null }

    private fun normalizeDomain(domain: String): String =
        domain.lowercase().removePrefix(".")

    /**
     * Set-Cookie 一行只保存第一个 name=value，Path/SameSite 等属性不是 Cookie。
     */
    private fun parseSetCookie(raw: String): List<Pair<String, String>> {
        val first = raw.substringBefore(';').trim()
        return parseCookieSegment(first)?.let { listOf(it) } ?: emptyList()
    }

    private fun parseCookieHeader(raw: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (segment in raw.split(';')) {
            parseCookieSegment(segment)?.let(result::add)
        }
        return result
    }

    private fun parseCookieSegment(segment: String): Pair<String, String>? {
        val trimmed = segment.trim()
        if (trimmed.isBlank()) return null
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx <= 0) return null
        val name = trimmed.substring(0, eqIdx).trim()
        if (name.isBlank() || name.any { it.isWhitespace() }) return null
        val value = trimmed.substring(eqIdx + 1).trim()
        return name to value
    }
}
