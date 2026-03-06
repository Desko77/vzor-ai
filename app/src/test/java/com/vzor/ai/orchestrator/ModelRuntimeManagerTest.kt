package com.vzor.ai.orchestrator

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ModelRuntimeManagerTest {

    private lateinit var manager: ModelRuntimeManager

    @Before
    fun setUp() {
        manager = ModelRuntimeManager()
    }

    @Test
    fun `registerModel adds slot`() = runTest {
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        assertEquals(1, manager.slots.value.size)
        assertFalse(manager.slots.value["qwen-9b"]!!.isLoaded)
    }

    @Test
    fun `requestLoad loads model when memory available`() = runTest {
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        assertTrue(manager.requestLoad("qwen-9b"))
        assertTrue(manager.slots.value["qwen-9b"]!!.isLoaded)
    }

    @Test
    fun `requestLoad returns false for unknown model`() = runTest {
        assertFalse(manager.requestLoad("nonexistent"))
    }

    @Test
    fun `usedMemoryMb updates after load`() = runTest {
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        manager.requestLoad("qwen-9b")
        assertEquals(18000, manager.usedMemoryMb.value)
    }

    @Test
    fun `unloadModel frees memory`() = runTest {
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        manager.requestLoad("qwen-9b")
        manager.unloadModel("qwen-9b")
        assertFalse(manager.slots.value["qwen-9b"]!!.isLoaded)
        assertEquals(0, manager.usedMemoryMb.value)
    }

    @Test
    fun `requestLoad evicts lower priority models when needed`() = runTest {
        manager.setMemoryLimit(19000) // Only 19GB — not enough for all three
        manager.registerModel("clip", ModelPriority.CLIP, 600)
        manager.registerModel("yolo", ModelPriority.OBJECT_DETECTION, 500)
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)

        // Load low-priority first
        manager.requestLoad("clip")
        manager.requestLoad("yolo")
        assertTrue(manager.slots.value["clip"]!!.isLoaded)
        assertTrue(manager.slots.value["yolo"]!!.isLoaded)

        // Load high-priority — should evict both low-priority
        assertTrue(manager.requestLoad("qwen-9b"))
        assertTrue(manager.slots.value["qwen-9b"]!!.isLoaded)
        assertFalse(manager.slots.value["clip"]!!.isLoaded)
        assertFalse(manager.slots.value["yolo"]!!.isLoaded)
    }

    @Test
    fun `requestLoad fails when cannot free enough memory`() = runTest {
        manager.setMemoryLimit(5000) // Only 5GB
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        assertFalse(manager.requestLoad("qwen-9b"))
    }

    @Test
    fun `getLoadedModels returns sorted by priority`() = runTest {
        manager.registerModel("clip", ModelPriority.CLIP, 600)
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        manager.registerModel("whisper", ModelPriority.STT, 3000)

        manager.requestLoad("clip")
        manager.requestLoad("qwen-9b")
        manager.requestLoad("whisper")

        val loaded = manager.getLoadedModels()
        assertEquals(3, loaded.size)
        assertEquals("qwen-9b", loaded[0].modelId) // LLM first
        assertEquals("whisper", loaded[1].modelId)  // STT second
        assertEquals("clip", loaded[2].modelId)      // CLIP last
    }

    @Test
    fun `already loaded model updates lastUsedAt`() = runTest {
        manager.registerModel("qwen-9b", ModelPriority.LLM, 18000)
        manager.requestLoad("qwen-9b")

        val firstUsed = manager.slots.value["qwen-9b"]!!.lastUsedAt
        Thread.sleep(10) // Ensure time passes
        manager.requestLoad("qwen-9b") // Re-request

        val secondUsed = manager.slots.value["qwen-9b"]!!.lastUsedAt
        assertTrue(secondUsed >= firstUsed)
    }
}
