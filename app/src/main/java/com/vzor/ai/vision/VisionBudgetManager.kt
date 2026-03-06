package com.vzor.ai.vision

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Token bucket rate limiter для Vision API запросов.
 * Контролирует частоту обращений к cloud VLM для предотвращения
 * превышения rate limits и оптимизации расходов.
 *
 * По умолчанию: 2 запроса в секунду, максимум 10 токенов в корзине.
 */
@Singleton
class VisionBudgetManager @Inject constructor() {

    companion object {
        private const val DEFAULT_RATE = 2.0        // токенов в секунду
        private const val DEFAULT_MAX_TOKENS = 10   // максимум в корзине
        private const val MIN_REFILL_INTERVAL_MS = 50L
    }

    private val mutex = Mutex()

    private var rate: Double = DEFAULT_RATE
    private var maxTokens: Int = DEFAULT_MAX_TOKENS
    private var availableTokens: Double = DEFAULT_MAX_TOKENS.toDouble()
    private var lastRefillTime: Long = System.currentTimeMillis()

    private val _remainingTokens = MutableStateFlow(DEFAULT_MAX_TOKENS)

    /** Текущее количество доступных токенов (для UI индикатора). */
    val remainingTokens: StateFlow<Int> = _remainingTokens.asStateFlow()

    /**
     * Пытается взять один токен без ожидания.
     *
     * @return true если токен доступен и был взят, false если бюджет исчерпан.
     */
    suspend fun tryAcquire(): Boolean = mutex.withLock {
        refill()
        if (availableTokens >= 1.0) {
            availableTokens -= 1.0
            updateState()
            true
        } else {
            false
        }
    }

    /**
     * Берёт токен, ожидая при необходимости.
     * Блокирует корутину до появления доступного токена.
     */
    suspend fun acquire() {
        while (true) {
            val acquired = mutex.withLock {
                refill()
                if (availableTokens >= 1.0) {
                    availableTokens -= 1.0
                    updateState()
                    true
                } else {
                    false
                }
            }
            if (acquired) return

            // Ждём время, необходимое для генерации одного токена
            val waitMs = (1000.0 / rate).toLong().coerceAtLeast(MIN_REFILL_INTERVAL_MS)
            delay(waitMs)
        }
    }

    /**
     * Устанавливает новый лимит запросов.
     *
     * @param tokensPerSecond Количество запросов в секунду.
     * @param maxBurst Максимум токенов в корзине (burst capacity).
     */
    suspend fun setBudgetRate(tokensPerSecond: Double, maxBurst: Int = DEFAULT_MAX_TOKENS) {
        mutex.withLock {
            rate = tokensPerSecond.coerceAtLeast(0.1)
            maxTokens = maxBurst.coerceAtLeast(1)
            availableTokens = availableTokens.coerceAtMost(maxTokens.toDouble())
            updateState()
        }
    }

    /**
     * Сбрасывает бюджет: корзина заполняется до максимума.
     */
    suspend fun resetBudget() {
        mutex.withLock {
            availableTokens = maxTokens.toDouble()
            lastRefillTime = System.currentTimeMillis()
            updateState()
        }
    }

    /**
     * Текущая заполненность бюджета (0.0 — пуст, 1.0 — полон).
     */
    suspend fun budgetFraction(): Float = mutex.withLock {
        refill()
        (availableTokens / maxTokens).toFloat().coerceIn(0f, 1f)
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillTime
        if (elapsed <= 0) return

        val tokensToAdd = elapsed * rate / 1000.0
        availableTokens = (availableTokens + tokensToAdd).coerceAtMost(maxTokens.toDouble())
        lastRefillTime = now
    }

    private fun updateState() {
        _remainingTokens.value = availableTokens.toInt()
    }
}
