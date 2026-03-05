package com.vzor.ai.vision

import com.vzor.ai.domain.model.SceneData
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PerceptionCache @Inject constructor() {

    /**
     * Default TTLs by analysis type.
     */
    object DefaultTtl {
        const val OBJECTS_MS = 30_000L
        const val TEXT_MS = 60_000L
        const val SCENE_DESCRIPTION_MS = 10_000L
    }

    data class CacheEntry(
        val data: SceneData,
        val timestamp: Long,
        val ttl: Long
    ) {
        fun isExpired(): Boolean = System.currentTimeMillis() - timestamp > ttl
    }

    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val hits = AtomicLong(0L)
    private val misses = AtomicLong(0L)

    /**
     * Stores a [SceneData] result under the given key with a specified TTL.
     */
    fun put(key: String, data: SceneData, ttlMs: Long) {
        cache[key] = CacheEntry(
            data = data,
            timestamp = System.currentTimeMillis(),
            ttl = ttlMs
        )
    }

    /**
     * Retrieves cached [SceneData] for the given key.
     * Returns null if the key is absent or the entry has expired.
     * Expired entries are eagerly removed.
     */
    fun get(key: String): SceneData? {
        val entry = cache[key]
        return if (entry != null && !entry.isExpired()) {
            hits.incrementAndGet()
            entry.data
        } else {
            if (entry != null) {
                cache.remove(key)
            }
            misses.incrementAndGet()
            null
        }
    }

    /**
     * Removes a specific cache entry by key.
     */
    fun invalidate(key: String) {
        cache.remove(key)
    }

    /**
     * Clears all cache entries and resets hit/miss counters.
     */
    fun invalidateAll() {
        cache.clear()
        hits.set(0L)
        misses.set(0L)
    }

    /**
     * Returns the cache hit ratio as a float between 0.0 and 1.0.
     * Returns 0.0 if no lookups have been performed.
     */
    fun hitRate(): Float {
        val totalHits = hits.get()
        val totalMisses = misses.get()
        val total = totalHits + totalMisses
        return if (total == 0L) 0f else totalHits.toFloat() / total.toFloat()
    }

    /**
     * Returns the number of non-expired entries currently in the cache.
     */
    fun size(): Int {
        evictExpired()
        return cache.size
    }

    private fun evictExpired() {
        val iterator = cache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isExpired()) {
                iterator.remove()
            }
        }
    }
}
