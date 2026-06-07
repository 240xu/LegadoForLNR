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
                // 格式: count/timeWindow
                val maxCount = parts[0].toIntOrNull() ?: return
                val timeWindow = parts[1].toLongOrNull() ?: return
                val now = System.currentTimeMillis()
                val accessList = accessCountMap.getOrPut(key) { mutableListOf() }
                synchronized(accessList) {
                    // 清除过期记录
                    accessList.removeAll { now - it > timeWindow }
                    if (accessList.size >= maxCount) {
                        val waitTime = timeWindow - (now - accessList.first())
                        if (waitTime > 0) Thread.sleep(waitTime)
                    }
                    accessList.add(System.currentTimeMillis())
                }
            } else {
                // 格式: intervalMs
                val interval = rateLimit.toLongOrNull() ?: return
                val lastAccess = lastAccessMap[key] ?: 0L
                val now = System.currentTimeMillis()
                val elapsed = now - lastAccess
                if (elapsed < interval) {
                    Thread.sleep(interval - elapsed)
                }
                lastAccessMap[key] = System.currentTimeMillis()
            }
        } catch (_: Exception) {}
    }
}