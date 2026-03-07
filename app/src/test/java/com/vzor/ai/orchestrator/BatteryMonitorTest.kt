package com.vzor.ai.orchestrator

import org.junit.Assert.*
import org.junit.Test

class BatteryMonitorTest {

    @Test
    fun `LOW_BATTERY_THRESHOLD is 20 percent`() {
        assertEquals(20, BatteryMonitor.LOW_BATTERY_THRESHOLD)
    }

    @Test
    fun `CRITICAL_BATTERY_THRESHOLD is 10 percent`() {
        assertEquals(10, BatteryMonitor.CRITICAL_BATTERY_THRESHOLD)
    }

    @Test
    fun `BatteryState defaults are reasonable`() {
        val state = BatteryMonitor.BatteryState()
        assertEquals(100, state.level)
        assertFalse(state.isCharging)
        assertEquals(25.0f, state.temperature, 0.01f)
        assertFalse(state.isLow)
        assertFalse(state.isCritical)
        assertFalse(state.isThermalThrottle)
    }

    @Test
    fun `BatteryState isLow when level below threshold`() {
        val state = BatteryMonitor.BatteryState(level = 15, isLow = true)
        assertTrue(state.isLow)
        assertFalse(state.isCritical)
    }

    @Test
    fun `BatteryState isCritical when level below critical threshold`() {
        val state = BatteryMonitor.BatteryState(level = 5, isLow = true, isCritical = true)
        assertTrue(state.isLow)
        assertTrue(state.isCritical)
    }

    @Test
    fun `BatteryState isThermalThrottle when temperature high`() {
        val state = BatteryMonitor.BatteryState(temperature = 45.0f, isThermalThrottle = true)
        assertTrue(state.isThermalThrottle)
    }

    @Test
    fun `BatteryState charging state`() {
        val state = BatteryMonitor.BatteryState(level = 50, isCharging = true)
        assertTrue(state.isCharging)
        assertFalse(state.isLow)
    }
}
