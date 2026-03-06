package com.vzor.ai.vision

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class SamplingMode(val fps: Int) {
    IDLE(0),
    LOW(1),
    MEDIUM(5),
    HIGH(10),
    BURST(15)
}

@Singleton
class FrameSampler @Inject constructor() {

    companion object {
        /** Окно для подсчёта событий (мс). */
        private const val EVENT_WINDOW_MS = 10_000L
        /** Порог событий для повышения FPS. */
        private const val HIGH_EVENT_THRESHOLD = 3
    }

    private val currentMode = AtomicReference(SamplingMode.IDLE)
    private val effectiveMode = AtomicReference(SamplingMode.IDLE)
    private val lastCaptureTimestamp = AtomicLong(0L)
    private val lowBattery = AtomicReference(false)
    private val isProcessing = AtomicBoolean(false)
    private val adaptiveEnabled = AtomicBoolean(false)

    /** Кольцевой буфер timestamp'ов недавних событий для адаптивного FPS. */
    private val recentEventTimestamps = java.util.concurrent.ConcurrentLinkedDeque<Long>()

    /**
     * Sets the desired sampling mode. The effective mode may be lower
     * if battery saver is active.
     */
    fun setMode(mode: SamplingMode) {
        currentMode.set(mode)
        recalculateEffectiveMode()
    }

    /**
     * Returns the currently requested mode (before battery adjustment).
     */
    fun getMode(): SamplingMode = currentMode.get()

    /**
     * Returns the effective mode after battery adjustments.
     */
    fun getEffectiveMode(): SamplingMode = effectiveMode.get()

    /**
     * Returns true when enough time has elapsed since the last capture
     * to warrant a new frame, based on the current effective fps.
     * Always returns false in IDLE mode.
     */
    /**
     * Устанавливает флаг обработки кадра для backpressure.
     * Если кадр обрабатывается, [shouldCaptureFrame] возвращает false.
     */
    fun setProcessing(processing: Boolean) {
        isProcessing.set(processing)
    }

    fun shouldCaptureFrame(): Boolean {
        val mode = effectiveMode.get()
        if (mode == SamplingMode.IDLE || mode.fps <= 0) return false
        if (isProcessing.get()) return false

        val intervalMs = 1000L / mode.fps
        val now = System.currentTimeMillis()
        val last = lastCaptureTimestamp.get()

        if (now - last >= intervalMs) {
            // CAS to prevent multiple threads from capturing the same frame window
            if (lastCaptureTimestamp.compareAndSet(last, now)) {
                return true
            }
        }
        return false
    }

    /**
     * Called when battery level changes. Automatically reduces effective fps
     * when battery drops below 20%.
     */
    fun onBatteryChanged(level: Int) {
        lowBattery.set(level < 20)
        recalculateEffectiveMode()
    }

    /**
     * Returns the interval in milliseconds between frames for the effective mode.
     * Returns [Long.MAX_VALUE] for IDLE mode.
     */
    fun getIntervalMs(): Long {
        val mode = effectiveMode.get()
        return if (mode == SamplingMode.IDLE || mode.fps <= 0) {
            Long.MAX_VALUE
        } else {
            1000L / mode.fps
        }
    }

    /**
     * Resets the last capture timestamp, useful when restarting capture.
     */
    fun reset() {
        lastCaptureTimestamp.set(0L)
        recentEventTimestamps.clear()
    }

    /**
     * Включает/выключает адаптивный FPS.
     * В адаптивном режиме FPS увеличивается при обнаружении частых событий
     * и снижается при отсутствии изменений сцены.
     */
    fun setAdaptiveMode(enabled: Boolean) {
        adaptiveEnabled.set(enabled)
        if (!enabled) recentEventTimestamps.clear()
        recalculateEffectiveMode()
    }

    /**
     * Сообщает FrameSampler'у о произошедшем vision event.
     * Используется для адаптивного повышения FPS при частых изменениях.
     */
    fun onVisionEvent() {
        if (!adaptiveEnabled.get()) return
        recentEventTimestamps.addLast(System.currentTimeMillis())
        pruneOldEvents()
        recalculateEffectiveMode()
    }

    /** Количество событий в текущем окне — для тестов и телеметрии. */
    fun getRecentEventCount(): Int {
        pruneOldEvents()
        return recentEventTimestamps.size
    }

    private fun pruneOldEvents() {
        val cutoff = System.currentTimeMillis() - EVENT_WINDOW_MS
        while (recentEventTimestamps.peekFirst()?.let { it < cutoff } == true) {
            recentEventTimestamps.pollFirst()
        }
    }

    private fun recalculateEffectiveMode() {
        val requested = currentMode.get()
        if (requested == SamplingMode.IDLE) {
            effectiveMode.set(SamplingMode.IDLE)
            return
        }

        // Адаптивное повышение: много событий → увеличиваем FPS на один уровень
        val adapted = if (adaptiveEnabled.get()) {
            pruneOldEvents()
            val eventCount = recentEventTimestamps.size
            if (eventCount >= HIGH_EVENT_THRESHOLD) {
                boostMode(requested)
            } else {
                requested
            }
        } else {
            requested
        }

        // Батарея имеет приоритет — ограничиваем до LOW
        if (lowBattery.get()) {
            val capped = when (adapted) {
                SamplingMode.IDLE -> SamplingMode.IDLE
                SamplingMode.LOW -> SamplingMode.LOW
                SamplingMode.MEDIUM,
                SamplingMode.HIGH,
                SamplingMode.BURST -> SamplingMode.LOW
            }
            effectiveMode.set(capped)
        } else {
            effectiveMode.set(adapted)
        }
    }

    /** Повышает режим на один уровень. */
    private fun boostMode(mode: SamplingMode): SamplingMode = when (mode) {
        SamplingMode.IDLE -> SamplingMode.IDLE
        SamplingMode.LOW -> SamplingMode.MEDIUM
        SamplingMode.MEDIUM -> SamplingMode.HIGH
        SamplingMode.HIGH -> SamplingMode.BURST
        SamplingMode.BURST -> SamplingMode.BURST
    }
}
