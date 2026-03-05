package com.vzor.ai.vision

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

    private val currentMode = AtomicReference(SamplingMode.IDLE)
    private val effectiveMode = AtomicReference(SamplingMode.IDLE)
    private val lastCaptureTimestamp = AtomicLong(0L)
    private val lowBattery = AtomicReference(false)

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
    fun shouldCaptureFrame(): Boolean {
        val mode = effectiveMode.get()
        if (mode == SamplingMode.IDLE || mode.fps <= 0) return false

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
    }

    private fun recalculateEffectiveMode() {
        val requested = currentMode.get()
        if (lowBattery.get()) {
            // Cap at LOW when battery is critically low
            val capped = when (requested) {
                SamplingMode.IDLE -> SamplingMode.IDLE
                SamplingMode.LOW -> SamplingMode.LOW
                SamplingMode.MEDIUM,
                SamplingMode.HIGH,
                SamplingMode.BURST -> SamplingMode.LOW
            }
            effectiveMode.set(capped)
        } else {
            effectiveMode.set(requested)
        }
    }
}
