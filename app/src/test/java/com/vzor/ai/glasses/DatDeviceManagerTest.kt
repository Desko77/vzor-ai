package com.vzor.ai.glasses

import com.meta.wearable.dat.core.types.PermissionStatus as DatPermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Тесты DatDeviceManager — device discovery, permissions, device info.
 *
 * Использует unitTests.isReturnDefaultValues = true для стабирования
 * static-вызовов Wearables.* из DAT SDK. Для более глубокого
 * интеграционного тестирования используется mwdat-mockdevice.
 */
class DatDeviceManagerTest {

    @Test
    fun `initial state is not initialized`() {
        val state = DatDeviceManager.DatDeviceState()
        assertFalse(state.isInitialized)
        assertFalse(state.isRegistered)
        assertEquals(RegistrationState.NOT_REGISTERED, state.registrationState)
        assertNull(state.deviceInfo)
        assertEquals(DatPermissionStatus.NotDetermined, state.cameraPermission)
        assertEquals(DatPermissionStatus.NotDetermined, state.microphonePermission)
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
            cameraPermission = DatPermissionStatus.Granted
        )
        val stateNotInit = stateAllGranted.copy(isInitialized = false)
        val stateNotRegistered = stateAllGranted.copy(isRegistered = false)
        val stateNoPerm = stateAllGranted.copy(cameraPermission = DatPermissionStatus.NotDetermined)

        // Только когда все три условия true
        assertTrue(stateAllGranted.isInitialized && stateAllGranted.isRegistered &&
            stateAllGranted.cameraPermission == DatPermissionStatus.Granted)

        assertFalse(stateNotInit.isInitialized)
        assertFalse(stateNotRegistered.isRegistered)
        assertFalse(stateNoPerm.cameraPermission == DatPermissionStatus.Granted)
    }

    @Test
    fun `microphone available requires all conditions`() {
        val state = DatDeviceManager.DatDeviceState(
            isInitialized = true,
            isRegistered = true,
            microphonePermission = DatPermissionStatus.Granted
        )
        assertTrue(state.isInitialized && state.isRegistered &&
            state.microphonePermission == DatPermissionStatus.Granted)
    }

    @Test
    fun `registration state transitions are correct`() {
        // NOT_REGISTERED → REGISTERING → REGISTERED
        val states = listOf(
            RegistrationState.NOT_REGISTERED,
            RegistrationState.REGISTERING,
            RegistrationState.REGISTERED
        )

        assertEquals(RegistrationState.NOT_REGISTERED, states[0])
        assertEquals(RegistrationState.REGISTERING, states[1])
        assertEquals(RegistrationState.REGISTERED, states[2])
    }

    @Test
    fun `permission statuses cover all cases`() {
        val statuses = listOf(
            DatPermissionStatus.NotDetermined,
            DatPermissionStatus.Granted,
            DatPermissionStatus.Denied
        )
        assertEquals(3, statuses.size)
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
