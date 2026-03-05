package com.vzor.ai.vision

import com.vzor.ai.domain.model.SceneData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerceptionCacheTest {

    private lateinit var cache: PerceptionCache

    @Before
    fun setUp() {
        cache = PerceptionCache()
    }

    // --- Put / Get ---

    @Test
    fun `put and get returns cached data`() {
        val scene = testScene("scene-1")
        cache.put("key1", scene, 30_000L)
        val result = cache.get("key1")
        assertNotNull(result)
        assertEquals("scene-1", result!!.sceneId)
    }

    @Test
    fun `get returns null for missing key`() {
        assertNull(cache.get("nonexistent"))
    }

    // --- TTL expiry ---

    @Test
    fun `expired entry returns null`() {
        val scene = testScene("expired")
        cache.put("key1", scene, 1L) // 1ms TTL
        Thread.sleep(10) // Wait for expiry
        assertNull(cache.get("key1"))
    }

    @Test
    fun `non-expired entry returns data`() {
        val scene = testScene("fresh")
        cache.put("key1", scene, 60_000L) // 60s TTL
        assertNotNull(cache.get("key1"))
    }

    // --- Invalidation ---

    @Test
    fun `invalidate removes entry`() {
        cache.put("key1", testScene("1"), 30_000L)
        cache.invalidate("key1")
        assertNull(cache.get("key1"))
    }

    @Test
    fun `invalidateAll clears all entries and counters`() {
        cache.put("key1", testScene("1"), 30_000L)
        cache.put("key2", testScene("2"), 30_000L)
        cache.get("key1") // hit
        cache.get("missing") // miss
        cache.invalidateAll()
        assertEquals(0, cache.size())
        assertEquals(0f, cache.hitRate(), 0.001f)
    }

    // --- Hit rate ---

    @Test
    fun `hit rate tracks correctly`() {
        cache.put("key1", testScene("1"), 30_000L)
        cache.get("key1") // hit
        cache.get("key1") // hit
        cache.get("missing") // miss
        assertEquals(2f / 3f, cache.hitRate(), 0.01f)
    }

    @Test
    fun `hit rate is 0 with no lookups`() {
        assertEquals(0f, cache.hitRate(), 0.001f)
    }

    // --- Size ---

    @Test
    fun `size returns count of non-expired entries`() {
        cache.put("key1", testScene("1"), 60_000L)
        cache.put("key2", testScene("2"), 1L) // expires immediately
        Thread.sleep(10)
        assertEquals(1, cache.size()) // only key1 remains
    }

    // --- Default TTLs ---

    @Test
    fun `default TTLs are correct`() {
        assertEquals(30_000L, PerceptionCache.DefaultTtl.OBJECTS_MS)
        assertEquals(60_000L, PerceptionCache.DefaultTtl.TEXT_MS)
        assertEquals(10_000L, PerceptionCache.DefaultTtl.SCENE_DESCRIPTION_MS)
    }

    // --- Helper ---

    private fun testScene(id: String) = SceneData(
        sceneId = id,
        timestamp = System.currentTimeMillis(),
        sceneSummary = "Test scene $id",
        objects = emptyList(),
        text = emptyList(),
        stability = 0.5f,
        ttlMs = 5000
    )
}
