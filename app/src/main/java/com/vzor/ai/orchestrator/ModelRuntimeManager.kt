package com.vzor.ai.orchestrator

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Приоритет моделей для Edge AI сервера (EVO X2 / AI Max).
 * Определяет порядок загрузки/выгрузки моделей при ограничении памяти.
 */
enum class ModelPriority(val weight: Int) {
    /** LLM (Qwen3.5-9B) — основной, всегда загружен. */
    LLM(100),
    /** STT (Whisper V3 Turbo) — активируется при голосовом вводе. */
    STT(80),
    /** Vision LLM (Qwen-VL 7B) — по запросу. */
    VISION(60),
    /** Object Detection (YOLOv8) — фоновый. */
    OBJECT_DETECTION(40),
    /** Zero-shot classification (CLIP) — низкий приоритет. */
    CLIP(20)
}

/**
 * Состояние загруженной модели на Edge AI.
 */
data class ModelSlot(
    val modelId: String,
    val priority: ModelPriority,
    val memoryMb: Int,
    val isLoaded: Boolean = false,
    val lastUsedAt: Long = 0L
)

/**
 * Управляет приоритетной очередью моделей на Edge AI сервере.
 *
 * При нехватке памяти выгружает модели с низким приоритетом,
 * чтобы освободить место для высокоприоритетных запросов.
 *
 * Типичная конфигурация EVO X2 (128GB RAM):
 * - Qwen3.5-9B: ~18GB (всегда загружена)
 * - Whisper V3 Turbo: ~3GB
 * - Qwen-VL 7B: ~14GB
 * - YOLOv8: ~0.5GB
 * - CLIP ViT-B/32: ~0.6GB
 * Total: ~36GB → 92GB свободно для ОС и буферов
 */
@Singleton
class ModelRuntimeManager @Inject constructor() {

    companion object {
        /** Лимит памяти для моделей (MB). Оставляем 32GB для ОС. */
        private const val DEFAULT_MEMORY_LIMIT_MB = 96_000
    }

    private val mutex = Mutex()

    private var memoryLimitMb: Int = DEFAULT_MEMORY_LIMIT_MB

    private val _slots = MutableStateFlow<Map<String, ModelSlot>>(emptyMap())
    /** Текущие слоты моделей (для UI/мониторинга). */
    val slots: StateFlow<Map<String, ModelSlot>> = _slots.asStateFlow()

    private val _usedMemoryMb = MutableStateFlow(0)
    /** Используемая память моделями (MB). */
    val usedMemoryMb: StateFlow<Int> = _usedMemoryMb.asStateFlow()

    /**
     * Регистрирует модель в менеджере.
     * Модель не загружается автоматически — используйте [requestLoad].
     */
    suspend fun registerModel(modelId: String, priority: ModelPriority, memoryMb: Int) {
        mutex.withLock {
            val current = _slots.value.toMutableMap()
            current[modelId] = ModelSlot(
                modelId = modelId,
                priority = priority,
                memoryMb = memoryMb
            )
            _slots.value = current
        }
    }

    /**
     * Запрашивает загрузку модели. Если памяти не хватает, выгружает
     * модели с более низким приоритетом.
     *
     * @return true если модель загружена (или уже была загружена), false если
     *         невозможно освободить достаточно памяти.
     */
    suspend fun requestLoad(modelId: String): Boolean = mutex.withLock {
        val current = _slots.value.toMutableMap()
        val slot = current[modelId] ?: return@withLock false

        if (slot.isLoaded) {
            // Обновляем lastUsedAt
            current[modelId] = slot.copy(lastUsedAt = System.currentTimeMillis())
            _slots.value = current
            return@withLock true
        }

        val used = current.values.filter { it.isLoaded }.sumOf { it.memoryMb }
        val available = memoryLimitMb - used

        if (slot.memoryMb <= available) {
            // Достаточно памяти — загружаем
            current[modelId] = slot.copy(isLoaded = true, lastUsedAt = System.currentTimeMillis())
            _slots.value = current
            updateMemoryUsage(current)
            return@withLock true
        }

        // Нужно освободить память — выгружаем менее приоритетные модели
        val needed = slot.memoryMb - available
        val freed = evictModels(current, slot.priority, needed)

        if (freed >= needed) {
            current[modelId] = slot.copy(isLoaded = true, lastUsedAt = System.currentTimeMillis())
            _slots.value = current
            updateMemoryUsage(current)
            return@withLock true
        }

        // Невозможно освободить достаточно памяти
        false
    }

    /**
     * Выгружает модель из памяти.
     */
    suspend fun unloadModel(modelId: String) {
        mutex.withLock {
            val current = _slots.value.toMutableMap()
            val slot = current[modelId] ?: return@withLock
            current[modelId] = slot.copy(isLoaded = false)
            _slots.value = current
            updateMemoryUsage(current)
        }
    }

    /**
     * Возвращает список загруженных моделей, отсортированных по приоритету.
     */
    fun getLoadedModels(): List<ModelSlot> {
        return _slots.value.values
            .filter { it.isLoaded }
            .sortedByDescending { it.priority.weight }
    }

    /**
     * Устанавливает лимит памяти для моделей.
     */
    suspend fun setMemoryLimit(limitMb: Int) {
        mutex.withLock {
            memoryLimitMb = limitMb.coerceAtLeast(1024)
        }
    }

    /**
     * Выгружает модели с приоритетом ниже [threshold] пока не освободится [neededMb].
     * @return количество освобождённых MB.
     */
    private fun evictModels(
        slots: MutableMap<String, ModelSlot>,
        threshold: ModelPriority,
        neededMb: Int
    ): Int {
        // Кандидаты на выгрузку: загруженные, приоритет ниже порога, сортируем по приоритету (↑) и LRU
        val candidates = slots.values
            .filter { it.isLoaded && it.priority.weight < threshold.weight }
            .sortedWith(compareBy<ModelSlot> { it.priority.weight }.thenBy { it.lastUsedAt })

        var freed = 0
        for (candidate in candidates) {
            slots[candidate.modelId] = candidate.copy(isLoaded = false)
            freed += candidate.memoryMb
            if (freed >= neededMb) break
        }
        return freed
    }

    private fun updateMemoryUsage(slots: Map<String, ModelSlot>) {
        _usedMemoryMb.value = slots.values.filter { it.isLoaded }.sumOf { it.memoryMb }
    }
}
