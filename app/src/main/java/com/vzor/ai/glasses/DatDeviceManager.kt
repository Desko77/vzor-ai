package com.vzor.ai.glasses

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управляет обнаружением устройств и информацией через Meta DAT SDK.
 *
 * Ответственность:
 * - Инициализация DAT SDK
 * - Мониторинг регистрации устройства
 * - Запрос и проверка разрешений (CAMERA)
 * - Lifecycle управление DAT SDK
 *
 * Разделение от GlassesManager:
 * - DatDeviceManager: SDK init, discovery, permissions
 * - GlassesManager: connection lifecycle, camera/audio streaming
 *
 * DAT SDK 0.4.0 real API:
 * - Wearables.initialize() is suspend
 * - RegistrationState is sealed class (not enum)
 * - PermissionStatus is sealed interface
 * - checkPermissionStatus() is suspend
 * - No requestPermission(), getDeviceInfo(), Permission.MICROPHONE in SDK
 */
@Singleton
class DatDeviceManager @Inject constructor() {

    companion object {
        private const val TAG = "DatDeviceManager"
    }

    /**
     * Состояние подключённого устройства DAT.
     */
    data class DatDeviceState(
        val isInitialized: Boolean = false,
        val isRegistered: Boolean = false,
        val deviceInfo: DeviceInfoSnapshot? = null,
        val cameraPermissionGranted: Boolean = false
    )

    /**
     * Снимок информации об устройстве (serializable, без SDK зависимостей).
     * Заполняется из DeviceMetadata StateFlow когда доступно.
     */
    data class DeviceInfoSnapshot(
        val modelName: String = "",
        val firmwareVersion: String = "",
        val serialNumber: String = "",
        val batteryLevel: Int = -1
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(DatDeviceState())
    val state: StateFlow<DatDeviceState> = _state.asStateFlow()

    private var registrationObserverJob: kotlinx.coroutines.Job? = null

    /**
     * Инициализирует Meta Wearables DAT SDK.
     * Безопасно вызывать повторно — идемпотентная операция.
     *
     * Wearables.initialize() is suspend в DAT SDK 0.4.0,
     * поэтому запускаем в coroutine scope.
     *
     * @param context Application context.
     * @return true если инициализация запущена.
     */
    fun initialize(context: android.content.Context): Boolean {
        if (_state.value.isInitialized) return true

        return try {
            scope.launch {
                try {
                    val result = Wearables.initialize(context)
                    if (result.isSuccess()) {
                        _state.value = _state.value.copy(isInitialized = true)
                        Log.d(TAG, "DAT SDK initialized")
                        startRegistrationObserver()
                        refreshPermissions()
                    } else {
                        Log.e(TAG, "DAT SDK init failed: ${result.errorOrNull()}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "DAT SDK initialization error", e)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "DAT SDK initialization failed", e)
            false
        }
    }

    /**
     * Запускает процесс регистрации устройства (pairing).
     * Требует Activity context для DAT SDK 0.4.0+.
     */
    fun startRegistration(activity: Activity) {
        if (!_state.value.isInitialized) {
            Log.w(TAG, "Cannot start registration — SDK not initialized")
            return
        }

        if (_state.value.isRegistered) {
            Log.d(TAG, "Device already registered")
            return
        }

        try {
            Wearables.startRegistration(activity)
            Log.d(TAG, "Registration flow started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start registration", e)
        }
    }

    /**
     * Отменяет регистрацию (отвязка устройства).
     */
    fun startUnregistration(activity: Activity) {
        if (!_state.value.isInitialized) return

        try {
            Wearables.startUnregistration(activity)
            Log.d(TAG, "Unregistration flow started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start unregistration", e)
        }
    }

    /**
     * Обновляет статус разрешения камеры.
     *
     * DAT SDK 0.4.0 не имеет Wearables.requestPermission().
     * Разрешения управляются через Meta AI companion app.
     */
    fun requestCameraPermission() {
        if (!_state.value.isInitialized) return

        scope.launch {
            refreshPermissions()
        }
        Log.d(TAG, "Camera permission status refresh requested")
    }

    /**
     * Проверяет доступна ли камера (init + registered + permission granted).
     */
    fun isCameraAvailable(): Boolean {
        val s = _state.value
        return s.isInitialized && s.isRegistered && s.cameraPermissionGranted
    }

    /**
     * Освобождает ресурсы.
     */
    fun release() {
        registrationObserverJob?.cancel()
        registrationObserverJob = null
        Log.d(TAG, "DatDeviceManager released")
    }

    // =================================================================
    // Internal
    // =================================================================

    private fun startRegistrationObserver() {
        registrationObserverJob?.cancel()
        registrationObserverJob = scope.launch {
            try {
                Wearables.registrationState.collectLatest { regState ->
                    // RegistrationState is sealed class in DAT SDK 0.4.0
                    val isRegistered = regState is RegistrationState.Registered
                    _state.value = _state.value.copy(
                        isRegistered = isRegistered
                    )
                    Log.d(TAG, "Registration state: $regState")

                    if (isRegistered) {
                        refreshPermissions()
                    } else {
                        _state.value = _state.value.copy(deviceInfo = null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Registration observer error", e)
            }
        }
    }

    /**
     * Обновляет статус разрешений.
     * checkPermissionStatus() is suspend в DAT SDK 0.4.0.
     */
    private suspend fun refreshPermissions() {
        if (!_state.value.isInitialized) return

        try {
            val cameraResult = Wearables.checkPermissionStatus(Permission.CAMERA)
            val cameraGranted = cameraResult.getOrNull() is PermissionStatus.Granted

            _state.value = _state.value.copy(
                cameraPermissionGranted = cameraGranted
            )
            Log.d(TAG, "Permissions: camera=$cameraGranted")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh permissions", e)
        }
    }
}
