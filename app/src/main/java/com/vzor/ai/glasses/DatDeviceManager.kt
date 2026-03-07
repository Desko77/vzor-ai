package com.vzor.ai.glasses

import android.app.Activity
import android.util.Log
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.DeviceInfo
import com.meta.wearable.dat.core.types.Permission as DatPermission
import com.meta.wearable.dat.core.types.PermissionStatus as DatPermissionStatus
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
 * - Запрос и проверка разрешений (CAMERA, MICROPHONE)
 * - Получение информации об устройстве (модель, firmware, батарея)
 * - Lifecycle управление DAT SDK
 *
 * Разделение от GlassesManager:
 * - DatDeviceManager: SDK init, discovery, permissions, device info
 * - GlassesManager: connection lifecycle, camera/audio streaming
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
        val registrationState: RegistrationState = RegistrationState.NOT_REGISTERED,
        val isRegistered: Boolean = false,
        val deviceInfo: DeviceInfoSnapshot? = null,
        val cameraPermission: DatPermissionStatus = DatPermissionStatus.NotDetermined,
        val microphonePermission: DatPermissionStatus = DatPermissionStatus.NotDetermined
    )

    /**
     * Снимок информации об устройстве (serializable, без SDK зависимостей).
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
     * @param context Application context.
     * @return true если SDK успешно инициализирован.
     */
    fun initialize(context: android.content.Context): Boolean {
        if (_state.value.isInitialized) return true

        return try {
            Wearables.initialize(context)

            _state.value = _state.value.copy(isInitialized = true)
            Log.d(TAG, "DAT SDK initialized")

            // Запускаем наблюдение за регистрацией
            startRegistrationObserver()

            // Обновляем разрешения
            refreshPermissions()

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
     * Запрашивает разрешение на камеру через DAT SDK.
     * DAT SDK показывает системный диалог на очках.
     */
    fun requestCameraPermission() {
        if (!_state.value.isInitialized) return

        try {
            Wearables.requestPermission(DatPermission.CAMERA)
            Log.d(TAG, "Camera permission requested")
            // Обновим статус после запроса
            refreshPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request camera permission", e)
        }
    }

    /**
     * Запрашивает разрешение на микрофон через DAT SDK.
     */
    fun requestMicrophonePermission() {
        if (!_state.value.isInitialized) return

        try {
            Wearables.requestPermission(DatPermission.MICROPHONE)
            Log.d(TAG, "Microphone permission requested")
            refreshPermissions()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request microphone permission", e)
        }
    }

    /**
     * Обновляет информацию об устройстве из DAT SDK.
     */
    fun refreshDeviceInfo() {
        if (!_state.value.isInitialized || !_state.value.isRegistered) return

        try {
            val infoResult = Wearables.getDeviceInfo()
            val info = infoResult?.getOrNull()
            if (info != null) {
                val snapshot = DeviceInfoSnapshot(
                    modelName = info.modelName ?: "",
                    firmwareVersion = info.firmwareVersion ?: "",
                    serialNumber = info.serialNumber ?: "",
                    batteryLevel = info.batteryLevel ?: -1
                )
                _state.value = _state.value.copy(deviceInfo = snapshot)
                Log.d(TAG, "Device info: model=${snapshot.modelName}, " +
                    "fw=${snapshot.firmwareVersion}, battery=${snapshot.batteryLevel}%")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get device info", e)
        }
    }

    /**
     * Проверяет доступна ли камера (init + registered + permission granted).
     */
    fun isCameraAvailable(): Boolean {
        val s = _state.value
        return s.isInitialized && s.isRegistered && s.cameraPermission == DatPermissionStatus.Granted
    }

    /**
     * Проверяет доступен ли микрофон.
     */
    fun isMicrophoneAvailable(): Boolean {
        val s = _state.value
        return s.isInitialized && s.isRegistered && s.microphonePermission == DatPermissionStatus.Granted
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
                    val isRegistered = regState == RegistrationState.REGISTERED
                    _state.value = _state.value.copy(
                        registrationState = regState,
                        isRegistered = isRegistered
                    )
                    Log.d(TAG, "Registration state: $regState")

                    // При регистрации обновляем info и permissions
                    if (isRegistered) {
                        refreshPermissions()
                        refreshDeviceInfo()
                    } else {
                        _state.value = _state.value.copy(deviceInfo = null)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Registration observer error", e)
            }
        }
    }

    private fun refreshPermissions() {
        if (!_state.value.isInitialized) return

        try {
            val cameraStatus = Wearables.checkPermissionStatus(DatPermission.CAMERA)
                ?.getOrNull() ?: DatPermissionStatus.NotDetermined
            val micStatus = Wearables.checkPermissionStatus(DatPermission.MICROPHONE)
                ?.getOrNull() ?: DatPermissionStatus.NotDetermined

            _state.value = _state.value.copy(
                cameraPermission = cameraStatus,
                microphonePermission = micStatus
            )
            Log.d(TAG, "Permissions: camera=$cameraStatus, mic=$micStatus")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh permissions", e)
        }
    }
}
