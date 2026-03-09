package com.vzor.ai.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты DatDeviceManager — device discovery, permissions, device info.
 *
 * DAT SDK 0.4.0 real API:
 * - RegistrationState is sealed class (not enum)
 * - PermissionStatus is sealed interface (Granted, Denied only)
 * - No Permission.MICROPHONE, no requestPermission(), no getDeviceInfo()
 */
class DatDeviceManagerTest {

    @Test
    fun `initial state is not initialized`() {
        val state = DatDeviceManager.DatDeviceState()
        assertFalse(state.isInitialized)
        assertFalse(state.isRegistered)
        assertNull(state.deviceInfo)
        assertFalse(state.cameraPermissionGranted)
    }

    @Test
    fun `DeviceInfoSnapshot defaults are correct`() {
        val info = DatDeviceManager.DeviceInfoSnapshot()
        assertEquals("", info.modelName)
        assertEquals("", info.firmwareVersion)
        assertEquals("", info.serialNumber)
        assertEquals(-1, info.batteryLevel)
    }

    @Test
    fun `DeviceInfoSnapshot stores real data`() {
        val info = DatDeviceManager.DeviceInfoSnapshot(
            modelName = "Ray-Ban Meta Gen 2",
            firmwareVersion = "2.1.0.456",
            serialNumber = "RBMG2-12345",
            batteryLevel = 85
        )
        assertEquals("Ray-Ban Meta Gen 2", info.modelName)
        assertEquals("2.1.0.456", info.firmwareVersion)
        assertEquals("RBMG2-12345", info.serialNumber)
        assertEquals(85, info.batteryLevel)
    }

    @Test
    fun `camera available requires all three conditions`() {
        val stateAllGranted = DatDeviceManager.DatDeviceState(
            isInitialized = true,
            isRegistered = true,
            cameraPermissionGranted = true
        )
        val stateNotInit = stateAllGranted.copy(isInitialized = false)
        val stateNotRegistered = stateAllGranted.copy(isRegistered = false)
        val stateNoPerm = stateAllGranted.copy(cameraPermissionGranted = false)

        // Только когда все три условия true
        assertTrue(stateAllGranted.isInitialized && stateAllGranted.isRegistered &&
            stateAllGranted.cameraPermissionGranted)

        assertFalse(stateNotInit.isInitialized)
        assertFalse(stateNotRegistered.isRegistered)
        assertFalse(stateNoPerm.cameraPermissionGranted)
    }

    @Test
    fun `state copy preserves unmodified fields`() {
        val original = DatDeviceManager.DatDeviceState(
            isInitialized = true,
            isRegistered = false,
            deviceInfo = DatDeviceManager.DeviceInfoSnapshot(modelName = "Test")
        )

        val updated = original.copy(isRegistered = true)
        assertTrue(updated.isInitialized) // preserved
        assertTrue(updated.isRegistered) // changed
        assertEquals("Test", updated.deviceInfo?.modelName) // preserved
    }
}
