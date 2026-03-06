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
