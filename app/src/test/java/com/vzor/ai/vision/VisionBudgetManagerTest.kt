package com.vzor.ai.vision

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VisionBudgetManagerTest {

    private lateinit var manager: VisionBudgetManager

    @Before
    fun setUp() {
        manager = VisionBudgetManager()
    }

    @Test
    fun `initial budget is full`() = runTest {
        val fraction = manager.budgetFraction()
        assertTrue("Начальный бюджет должен быть полным", fraction > 0.9f)
    }

    @Test
    fun `tryAcquire succeeds when budget available`() = runTest {
        assertTrue(manager.tryAcquire())
    }

    @Test
    fun `tryAcquire depletes tokens`() = runTest {
        // Берём все 10 токенов по умолчанию
        var acquired = 0
        repeat(10) {
            if (manager.tryAcquire()) acquired++
        }
        assertEquals("Должны взять 10 токенов", 10, acquired)

        // Следующий должен не пройти (без рефилла)
        assertFalse("11-й токен не должен быть доступен", manager.tryAcquire())
    }

    @Test
    fun `remainingTokens flow updates after acquire`() = runTest {
        val initial = manager.remainingTokens.value
        manager.tryAcquire()
        val after = manager.remainingTokens.value
        assertTrue("Осталось меньше токенов", after < initial)
    }

    @Test
    fun `resetBudget fills tokens to max`() = runTest {
        // Расходуем несколько токенов
        repeat(5) { manager.tryAcquire() }

        manager.resetBudget()

        val fraction = manager.budgetFraction()
        assertEquals("После сброса бюджет полный", 1.0f, fraction, 0.01f)
    }

    @Test
    fun `setBudgetRate changes capacity`() = runTest {
        manager.setBudgetRate(tokensPerSecond = 1.0, maxBurst = 3)
        manager.resetBudget()

        var acquired = 0
        repeat(5) {
            if (manager.tryAcquire()) acquired++
        }
        assertEquals("С maxBurst=3 можно взять 3 токена", 3, acquired)
    }

    @Test
    fun `setBudgetRate rejects invalid values`() = runTest {
        // Минимальный rate = 0.1
        manager.setBudgetRate(tokensPerSecond = 0.0, maxBurst = 0)
        manager.resetBudget()

        // maxBurst coerce'd to 1
        var acquired = 0
        repeat(3) {
            if (manager.tryAcquire()) acquired++
        }
        assertEquals("С maxBurst=1 можно взять 1 токен", 1, acquired)
    }

    @Test
    fun `budgetFraction returns zero when empty`() = runTest {
        repeat(10) { manager.tryAcquire() }
        val fraction = manager.budgetFraction()
        assertTrue("Пустой бюджет ≈ 0", fraction < 0.15f)
    }
}
