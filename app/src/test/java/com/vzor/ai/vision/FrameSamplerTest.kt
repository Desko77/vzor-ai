package com.vzor.ai.vision

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FrameSamplerTest {

    private lateinit var sampler: FrameSampler

    @Before
    fun setUp() {
        sampler = FrameSampler()
    }

    @Test
    fun `initial mode is IDLE`() {
        assertEquals(SamplingMode.IDLE, sampler.getMode())
        assertEquals(SamplingMode.IDLE, sampler.getEffectiveMode())
    }

    @Test
    fun `setMode updates requested mode`() {
        sampler.setMode(SamplingMode.HIGH)
        assertEquals(SamplingMode.HIGH, sampler.getMode())
        assertEquals(SamplingMode.HIGH, sampler.getEffectiveMode())
    }

    @Test
    fun `shouldCaptureFrame returns false in IDLE`() {
        assertFalse(sampler.shouldCaptureFrame())
    }

    @Test
    fun `shouldCaptureFrame returns true when interval elapsed`() {
        sampler.setMode(SamplingMode.LOW) // 1 fps -> 1000ms interval
        // Первый кадр всегда можно захватить (lastCaptureTimestamp = 0)
        assertTrue(sampler.shouldCaptureFrame())
    }

    @Test
    fun `shouldCaptureFrame returns false when processing`() {
        sampler.setMode(SamplingMode.HIGH)
        sampler.setProcessing(true)
        assertFalse(sampler.shouldCaptureFrame())
    }

    @Test
    fun `low battery caps mode to LOW`() {
        sampler.setMode(SamplingMode.HIGH)
        sampler.onBatteryChanged(15) // < 20%
        assertEquals(SamplingMode.HIGH, sampler.getMode()) // requested unchanged
        assertEquals(SamplingMode.LOW, sampler.getEffectiveMode()) // capped
    }

    @Test
    fun `battery recovery restores mode`() {
        sampler.setMode(SamplingMode.MEDIUM)
        sampler.onBatteryChanged(10)
        assertEquals(SamplingMode.LOW, sampler.getEffectiveMode())

        sampler.onBatteryChanged(80)
        assertEquals(SamplingMode.MEDIUM, sampler.getEffectiveMode())
    }

    @Test
    fun `getIntervalMs returns MAX_VALUE for IDLE`() {
        assertEquals(Long.MAX_VALUE, sampler.getIntervalMs())
    }

    @Test
    fun `getIntervalMs matches fps for active mode`() {
        sampler.setMode(SamplingMode.MEDIUM) // 5 fps
        assertEquals(200L, sampler.getIntervalMs())
    }

    @Test
    fun `reset clears state`() {
        sampler.setMode(SamplingMode.HIGH)
        sampler.shouldCaptureFrame() // consume first frame
        sampler.reset()
        // После reset можно захватить кадр снова
        assertTrue(sampler.shouldCaptureFrame())
    }

    // --- Adaptive FPS ---

    @Test
    fun `adaptive mode disabled by default`() {
        sampler.setMode(SamplingMode.LOW)
        // Без адаптивного режима события не влияют
        repeat(10) { sampler.onVisionEvent() }
        assertEquals(SamplingMode.LOW, sampler.getEffectiveMode())
    }

    @Test
    fun `adaptive mode boosts FPS on frequent events`() {
        sampler.setMode(SamplingMode.LOW)
        sampler.setAdaptiveMode(true)

        // 3+ событий (HIGH_EVENT_THRESHOLD) → boost LOW → MEDIUM
        repeat(3) { sampler.onVisionEvent() }

        assertEquals(SamplingMode.LOW, sampler.getMode()) // requested unchanged
        assertEquals(SamplingMode.MEDIUM, sampler.getEffectiveMode()) // boosted
    }

    @Test
    fun `adaptive mode does not boost beyond BURST`() {
        sampler.setMode(SamplingMode.BURST)
        sampler.setAdaptiveMode(true)

        repeat(5) { sampler.onVisionEvent() }
        assertEquals(SamplingMode.BURST, sampler.getEffectiveMode())
    }

    @Test
    fun `adaptive boost still capped by low battery`() {
        sampler.setMode(SamplingMode.LOW)
        sampler.setAdaptiveMode(true)
        sampler.onBatteryChanged(10) // low battery

        repeat(5) { sampler.onVisionEvent() }
        // Boosted to MEDIUM, but battery caps back to LOW
        assertEquals(SamplingMode.LOW, sampler.getEffectiveMode())
    }

    @Test
    fun `getRecentEventCount tracks events`() {
        sampler.setAdaptiveMode(true)
        assertEquals(0, sampler.getRecentEventCount())

        repeat(4) { sampler.onVisionEvent() }
        assertEquals(4, sampler.getRecentEventCount())
    }

    @Test
    fun `disabling adaptive clears events`() {
        sampler.setAdaptiveMode(true)
        repeat(5) { sampler.onVisionEvent() }
        sampler.setAdaptiveMode(false)
        assertEquals(0, sampler.getRecentEventCount())
    }

    @Test
    fun `IDLE mode not affected by adaptive`() {
        sampler.setMode(SamplingMode.IDLE)
        sampler.setAdaptiveMode(true)
        repeat(10) { sampler.onVisionEvent() }
        assertEquals(SamplingMode.IDLE, sampler.getEffectiveMode())
    }
}
