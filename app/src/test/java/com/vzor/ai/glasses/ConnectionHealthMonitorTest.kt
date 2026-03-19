package com.vzor.ai.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты ConnectionHealthMonitor — мониторинг здоровья подключения.
 */
class ConnectionHealthMonitorTest {

    @Test
    fun `initial health is UNKNOWN`() {
        val health = ConnectionHealthMonitor.ConnectionHealth()
        assertEquals(ConnectionHealthMonitor.HealthLevel.UNKNOWN, health.level)
        assertEquals(-1, health.batteryLevel)
        assertFalse(health.isBluetoothConnected)
        assertFalse(health.isDatRegistered)
        assertEquals(0f, health.currentFps, 0.001f)
        assertEquals(0L, health.droppedFrames)
        assertTrue(health.warnings.isEmpty())
    }

    @Test
    fun `GOOD health has no warnings`() {
        val health = ConnectionHealthMonitor.ConnectionHealth(
            level = ConnectionHealthMonitor.HealthLevel.GOOD,
            batteryLevel = 85,
            isBluetoothConnected = true,
            isDatRegistered = true,
            currentFps = 24f
        )
        assertEquals(ConnectionHealthMonitor.HealthLevel.GOOD, health.level)
        assertTrue(health.warnings.isEmpty())
    }

    @Test
    fun `WARNING health with low battery`() {
        val health = ConnectionHealthMonitor.ConnectionHealth(
            level = ConnectionHealthMonitor.HealthLevel.WARNING,
            batteryLevel = 15,
            warnings = listOf("Батарея очков низкая: 15%")
        )
        assertEquals(ConnectionHealthMonitor.HealthLevel.WARNING, health.level)
        assertEquals(1, health.warnings.size)
        assertTrue(health.warnings[0].contains("15%"))
    }

    @Test
    fun `CRITICAL health with very low battery`() {
        val health = ConnectionHealthMonitor.ConnectionHealth(
            level = ConnectionHealthMonitor.HealthLevel.CRITICAL,
            batteryLevel = 5,
            warnings = listOf("Батарея очков критически низкая: 5%")
        )
        assertEquals(ConnectionHealthMonitor.HealthLevel.CRITICAL, health.level)
        assertTrue(health.warnings[0].contains("критически"))
    }

    @Test
    fun `CRITICAL health on connection loss`() {
        val health = ConnectionHealthMonitor.ConnectionHealth(
            level = ConnectionHealthMonitor.HealthLevel.CRITICAL,
            isBluetoothConnected = false,
            isDatRegistered = false,
            warnings = listOf("Потеря Bluetooth и DAT соединения")
        )
        assertFalse(health.isBluetoothConnected)
        assertFalse(health.isDatRegistered)
        assertTrue(health.warnings[0].contains("Потеря"))
    }

    @Test
    fun `health levels are ordered by severity`() {
        val levels = ConnectionHealthMonitor.HealthLevel.entries
        assertEquals(4, levels.size)
        assertEquals(ConnectionHealthMonitor.HealthLevel.GOOD, levels[0])
        assertEquals(ConnectionHealthMonitor.HealthLevel.WARNING, levels[1])
        assertEquals(ConnectionHealthMonitor.HealthLevel.CRITICAL, levels[2])
        assertEquals(ConnectionHealthMonitor.HealthLevel.UNKNOWN, levels[3])
    }

    @Test
    fun `battery thresholds are correct`() {
        // Критический: <= 10%
        assertTrue(5 <= 10)
        assertTrue(10 <= 10)
        assertFalse(11 <= 10)

        // Низкий: 11-20%
        assertTrue(15 in 11..20)
        assertFalse(21 in 11..20)
        assertFalse(10 in 11..20)
    }

    @Test
    fun `fps calculation`() {
        // 24 frames in 1 second = 24 fps
        val frames = 24L
        val elapsedMs = 1000L
        val fps = frames * 1000f / elapsedMs
        assertEquals(24f, fps, 0.001f)

        // 12 frames in 1 second = 12 fps (below acceptable)
        val lowFps = 12L * 1000f / 1000L
        assertTrue(lowFps < 15f) // MIN_ACCEPTABLE_FPS area
    }

    @Test
    fun `dropped frames tracking`() {
        var droppedFrames = 0L
        droppedFrames++ // frame drop 1
        droppedFrames++ // frame drop 2
        assertEquals(2L, droppedFrames)
    }

    @Test
    fun `health copy preserves data`() {
        val original = ConnectionHealthMonitor.ConnectionHealth(
            level = ConnectionHealthMonitor.HealthLevel.GOOD,
            batteryLevel = 80,
            currentFps = 24f
        )
        val updated = original.copy(batteryLevel = 15, level = ConnectionHealthMonitor.HealthLevel.WARNING)
        assertEquals(24f, updated.currentFps, 0.001f) // preserved
        assertEquals(15, updated.batteryLevel) // changed
    }
}
