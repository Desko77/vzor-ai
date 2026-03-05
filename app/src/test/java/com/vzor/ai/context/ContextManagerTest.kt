package com.vzor.ai.context

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.MemoryCategory
import com.vzor.ai.domain.model.MemoryFact
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContextManagerTest {

    private lateinit var manager: ContextManager
    private val memoryRepo = mockk<MemoryRepository>(relaxed = true)
    private val prefs = mockk<PreferencesManager>(relaxed = true)

    @Before
    fun setUp() {
        manager = ContextManager(memoryRepo, prefs)
    }

    // --- Token estimation ---

    @Test
    fun `token estimate is roughly 1 per 4 chars`() {
        assertEquals(5, manager.getTokenEstimate("12345678901234567890")) // 20/4=5
        assertEquals(0, manager.getTokenEstimate(""))
        assertEquals(1, manager.getTokenEstimate("abcd"))
    }

    // --- Session memory ---

    @Test
    fun `addToSession and getSessionContext returns messages`() {
        val msg = testMessage("Привет")
        manager.addToSession(msg)
        val context = manager.getSessionContext()
        assertEquals(1, context.size)
        assertEquals("Привет", context[0].content)
    }

    @Test
    fun `clearSession removes all messages`() {
        manager.addToSession(testMessage("Привет"))
        manager.addToSession(testMessage("Как дела"))
        manager.clearSession()
        assertTrue(manager.getSessionContext().isEmpty())
    }

    // --- Token budget eviction ---

    @Test
    fun `evicts oldest messages when over budget`() {
        // MAX_SESSION_TOKENS = 2048, ~4 chars per token
        // Fill with messages that exceed budget
        val longContent = "A".repeat(4 * 1000) // ~1000 tokens each
        manager.addToSession(testMessage(longContent, "1"))
        manager.addToSession(testMessage(longContent, "2"))
        manager.addToSession(testMessage(longContent, "3")) // total ~3000 tokens > 2048

        val context = manager.getSessionContext()
        // Should have evicted at least the oldest message
        assertTrue(context.size < 3)
    }

    @Test
    fun `getSessionContext trims from newest keeping most recent`() {
        val shortContent = "A".repeat(4 * 500) // ~500 tokens
        repeat(6) { i ->
            manager.addToSession(testMessage(shortContent, i.toString()))
        }
        // 6 * 500 = 3000 > 2048 budget
        val context = manager.getSessionContext()
        // Should keep ~4 newest messages (4*500=2000 < 2048)
        assertTrue(context.size <= 5)
        assertTrue(context.isNotEmpty())
    }

    // --- Persistent facts ---

    @Test
    fun `getPersistentFacts with blank query returns top facts`() = runTest {
        coEvery { memoryRepo.getTopFacts(10) } returns emptyList()
        val facts = manager.getPersistentFacts("", 10)
        assertTrue(facts.isEmpty())
    }

    @Test
    fun `getPersistentFacts with query searches first`() = runTest {
        coEvery { memoryRepo.searchFacts("парковка", 10) } returns emptyList()
        coEvery { memoryRepo.getTopFacts(10) } returns emptyList()
        val facts = manager.getPersistentFacts("парковка", 10)
        // Falls back to top facts when search is empty
        assertTrue(facts.isEmpty())
    }

    // --- Empty state ---

    @Test
    fun `getSessionContext on empty session returns empty list`() {
        val newManager = ContextManager(memoryRepo, prefs)
        val context = newManager.getSessionContext()
        assertTrue(context.isEmpty())
    }

    // --- Boundary conditions ---

    @Test
    fun `message exactly at budget is kept`() {
        // 2048 tokens * 4 chars/token = 8192 chars
        val exactContent = "A".repeat(4 * 2048)
        manager.addToSession(testMessage(exactContent, "exact"))
        val context = manager.getSessionContext()
        assertEquals(1, context.size)
        assertEquals("exact", context[0].id)
    }

    @Test
    fun `adding many messages evicts oldest keeping newest`() {
        // Add 25 messages of ~100 tokens each (400 chars)
        // 25 * 100 = 2500 tokens > 2048 budget
        repeat(25) { i ->
            manager.addToSession(testMessage("A".repeat(400), "msg_$i"))
        }
        val context = manager.getSessionContext()
        // Should keep only messages that fit in budget
        assertTrue("Expected <= 20 messages, got ${context.size}", context.size <= 20)
        assertTrue(context.isNotEmpty())
        // Last message should be the most recent
        assertEquals("msg_24", context.last().id)
    }

    @Test
    fun `getPersistentFacts with query returns search results when available`() = runTest {
        val fact = MemoryFact(
            id = 1,
            fact = "Машина припаркована у ТЦ",
            category = MemoryCategory.PERSONAL,
            importance = 5,
            createdAt = System.currentTimeMillis()
        )
        coEvery { memoryRepo.searchFacts("парковка", 10) } returns listOf(fact)
        val facts = manager.getPersistentFacts("парковка", 10)
        assertEquals(1, facts.size)
        assertEquals("Машина припаркована у ТЦ", facts[0].fact)
    }

    // --- Helpers ---

    private fun testMessage(content: String, id: String = "test") = Message(
        id = id,
        role = MessageRole.USER,
        content = content
    )
}
