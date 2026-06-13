package io.legado.engine.http

import java.util.concurrent.ConcurrentHashMap

/**
 * 并发率限制器
 * 对应 Legado ConcurrentRateLimiter
 * 支持两种格式：
 * - "1000" → 访问间隔1000ms
 * - "20/60000" → 60s内访问次数20
 */
object ConcurrentRateLimiter {
    private val lastAccessMap = ConcurrentHashMap<String, Long>()
    private val accessCountMap = ConcurrentHashMap<String, MutableList<Long>>()

    fun acquire(key: String, rateLimit: String?) {
        if (rateLimit.isNullOrBlank()) return
        try {
            val parts = rateLimit.split("/")
            if (parts.size == 2) {
                val maxCount = parts[0].toIntOrNull() ?: return
                val timeWindow = parts[1].toLongOrNull() ?: return
                var waitTime = 0L
                val accessList = accessCountMap.getOrPut(key) { mutableListOf() }
                synchronized(accessList) {
                    val now = System.currentTimeMillis()
                    accessList.removeAll { now - it > timeWindow }
                    if (accessList.size >= maxCount) {
                        waitTime = timeWindow - (now - accessList.first())
                    }
                    if (waitTime <= 0) accessList.add(System.currentTimeMillis())
                }
                if (waitTime > 0) {
                    Thread.sleep(waitTime)
                    synchronized(accessList) { accessList.add(System.currentTimeMillis()) }
                }
            } else {
                val interval = rateLimit.toLongOrNull() ?: return
                lastAccessMap.compute(key) { _, lastAccess ->
                    val now = System.currentTimeMillis()
                    val elapsed = now - (lastAccess ?: 0L)
                    if (elapsed < interval) {
                        Thread.sleep(interval - elapsed)
                    }
                    System.currentTimeMillis()
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {}
    }
}
