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
        parsePairs(cookie).forEach { (name, value) -> map[name] = value }
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
        parsePairs(cookie).forEach { (name, value) -> map[name] = value }
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
     * 将原始 Set-Cookie / Cookie 字符串拆成 name=value 对。
     * - Set-Cookie 格式：sid=abc; Path=/; HttpOnly -> 只取 sid=abc
     * - Cookie header 格式：=1; b=2; c=3 -> 返回 a=1, b=2, c=3
     *
     * 策略：按 ; 拆分，每段若包含 = 且第一个 = 前的 trim 后不含空格，
     * 视为 name=value；其余为属性，忽略。
     */
    private fun parsePairs(raw: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (segment in raw.split(';')) {
            val trimmed = segment.trim()
            if (trimmed.isBlank()) continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx <= 0) continue
            val name = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            result.add(name to value)
        }
        return result
    }
}