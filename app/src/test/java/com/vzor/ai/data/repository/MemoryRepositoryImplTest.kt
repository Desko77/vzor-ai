package com.vzor.ai.data.repository

import com.vzor.ai.data.local.MemoryFactDao
import com.vzor.ai.data.local.MemoryFactEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MemoryRepositoryImplTest {

    private val dao = mockk<MemoryFactDao>(relaxed = true)
    private lateinit var repo: MemoryRepositoryImpl

    @Before
    fun setUp() {
        repo = MemoryRepositoryImpl(dao)
    }

    @Test
    fun `cleanup deletes excess facts when over limit`() = runTest {
        val topFacts = listOf(entity(1, 5), entity(2, 4), entity(3, 3))
        val allFacts = topFacts + listOf(entity(4, 1), entity(5, 1))

        coEvery { dao.getCount() } returns 5
        coEvery { dao.getTopFacts(3) } returns topFacts
        coEvery { dao.getAll() } returns allFacts

        repo.cleanup(3)

        coVerify { dao.deleteById(4) }
        coVerify { dao.deleteById(5) }
        coVerify(exactly = 0) { dao.deleteById(1) }
        coVerify(exactly = 0) { dao.deleteById(2) }
        coVerify(exactly = 0) { dao.deleteById(3) }
    }

    @Test
    fun `cleanup is no-op when under limit`() = runTest {
        coEvery { dao.getCount() } returns 3

        repo.cleanup(5)

        coVerify(exactly = 0) { dao.getTopFacts(any()) }
        coVerify(exactly = 0) { dao.getAll() }
        coVerify(exactly = 0) { dao.deleteById(any()) }
    }

    @Test
    fun `searchFacts updates lastAccessedAt for returned results`() = runTest {
        val entities = listOf(entity(10), entity(20))
        coEvery { dao.searchByKeyword("test") } returns entities

        repo.searchFacts("test", 10)

        coVerify { dao.updateAccessTime(10, any()) }
        coVerify { dao.updateAccessTime(20, any()) }
    }

    @Test
    fun `saveFact clamps importance to valid range`() = runTest {
        val entitySlot = slot<MemoryFactEntity>()
        coEvery { dao.insert(capture(entitySlot)) } returns 1L

        repo.saveFact("high importance", "PERSONAL", 10)
        assertEquals(5, entitySlot.captured.importance)

        repo.saveFact("low importance", "PERSONAL", -1)
        assertEquals(1, entitySlot.captured.importance)
    }

    @Test
    fun `getTopFacts delegates to dao and maps to domain`() = runTest {
        val entities = listOf(entity(1, 5), entity(2, 3))
        coEvery { dao.getTopFacts(5) } returns entities

        val result = repo.getTopFacts(5)

        assertEquals(2, result.size)
        assertEquals("fact1", result[0].fact)
        assertEquals(5, result[0].importance)
        assertEquals("fact2", result[1].fact)
        assertEquals(3, result[1].importance)
        coVerify { dao.getTopFacts(5) }
    }

    // --- LRU eviction verification ---

    @Test
    fun `cleanup evicts lowest importance facts first`() = runTest {
        // 5 facts: importance 5, 4, 3, 2, 1 — keep top 3
        val topFacts = listOf(entity(1, 5), entity(2, 4), entity(3, 3))
        val lowFacts = listOf(entity(4, 2), entity(5, 1))
        val allFacts = topFacts + lowFacts

        coEvery { dao.getCount() } returns 5
        coEvery { dao.getTopFacts(3) } returns topFacts
        coEvery { dao.getAll() } returns allFacts

        repo.cleanup(3)

        // Low importance facts should be evicted
        coVerify { dao.deleteById(4) }
        coVerify { dao.deleteById(5) }
        // High importance facts should be preserved
        coVerify(exactly = 0) { dao.deleteById(1) }
        coVerify(exactly = 0) { dao.deleteById(2) }
        coVerify(exactly = 0) { dao.deleteById(3) }
    }

    @Test
    fun `cleanup handles exact limit boundary`() = runTest {
        coEvery { dao.getCount() } returns 100

        val topFacts = (1L..100L).map { entity(it, (it % 5 + 1).toInt()) }
        coEvery { dao.getTopFacts(100) } returns topFacts
        coEvery { dao.getAll() } returns topFacts

        repo.cleanup(100)

        // All facts are in topFacts set — nothing deleted
        coVerify(exactly = 0) { dao.deleteById(any()) }
    }

    @Test
    fun `cleanup with maxFacts=0 deletes everything`() = runTest {
        val allFacts = listOf(entity(1, 5), entity(2, 3))

        coEvery { dao.getCount() } returns 2
        coEvery { dao.getTopFacts(0) } returns emptyList()
        coEvery { dao.getAll() } returns allFacts

        repo.cleanup(0)

        coVerify { dao.deleteById(1) }
        coVerify { dao.deleteById(2) }
    }

    @Test
    fun `searchFacts respects limit parameter`() = runTest {
        val entities = (1L..10L).map { entity(it) }
        coEvery { dao.searchByKeyword("test") } returns entities

        val result = repo.searchFacts("test", 3)

        assertEquals(3, result.size)
        // Only first 3 should have access time updated
        coVerify(exactly = 3) { dao.updateAccessTime(any(), any()) }
    }

    @Test
    fun `searchFacts returns empty for no matches`() = runTest {
        coEvery { dao.searchByKeyword("nonexistent") } returns emptyList()

        val result = repo.searchFacts("nonexistent", 10)

        assertTrue(result.isEmpty())
        coVerify(exactly = 0) { dao.updateAccessTime(any(), any()) }
    }

    @Test
    fun `saveFact stores correct fields`() = runTest {
        val entitySlot = slot<MemoryFactEntity>()
        coEvery { dao.insert(capture(entitySlot)) } returns 42L

        val id = repo.saveFact("Парковка на Ленина 15", "LOCATION", 4)

        assertEquals(42L, id)
        assertEquals("Парковка на Ленина 15", entitySlot.captured.fact)
        assertEquals("LOCATION", entitySlot.captured.category)
        assertEquals(4, entitySlot.captured.importance)
        assertEquals(0, entitySlot.captured.accessCount)
        assertTrue(entitySlot.captured.createdAt > 0)
        assertEquals(entitySlot.captured.createdAt, entitySlot.captured.lastAccessedAt)
    }

    @Test
    fun `deleteFact delegates to dao`() = runTest {
        repo.deleteFact(42)
        coVerify { dao.deleteById(42) }
    }

    private fun entity(id: Long, importance: Int = 3) = MemoryFactEntity(
        id = id,
        fact = "fact$id",
        category = "PERSONAL",
        importance = importance,
        createdAt = 1000L,
        lastAccessedAt = 1000L,
        accessCount = 0
    )
}
