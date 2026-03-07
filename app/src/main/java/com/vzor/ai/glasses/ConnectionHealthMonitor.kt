package com.vzor.ai.glasses

import android.util.Log
import com.vzor.ai.domain.model.GlassesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Мониторинг здоровья подключения к очкам.
 *
 * Периодически опрашивает:
 * - Уровень батареи очков (через DatDeviceManager)
 * - Состояние Bluetooth соединения
 * - Качество стрима камеры (fps, потери кадров)
 *
 * Генерирует [ConnectionHealth] события для UI-отображения.
 */
@Singleton
class ConnectionHealthMonitor @Inject constructor(
    private val datDeviceManager: DatDeviceManager
) {

    companion object {
        private const val TAG = "ConnectionHealthMonitor"

        /** Интервал опроса батареи и здоровья (ms). */
        private const val POLL_INTERVAL_MS = 30_000L

        /** Критически низкий уровень батареи (%). */
        private const val BATTERY_CRITICAL_THRESHOLD = 10

        /** Низкий уровень батареи (%). */
        private const val BATTERY_LOW_THRESHOLD = 20

        /** Минимальный приемлемый FPS для стрима. */
        private const val MIN_ACCEPTABLE_FPS = 10
    }

    /**
     * Общая оценка здоровья подключения.
     */
    enum class HealthLevel {
        /** Всё отлично. */
        GOOD,
        /** Незначительные проблемы (низкая батарея, низкий fps). */
        WARNING,
        /** Критические проблемы (батарея < 10%, потеря соединения). */
        CRITICAL,
        /** Не подключен. */
        UNKNOWN
    }

    /**
     * Снимок здоровья подключения.
     */
    data class ConnectionHealth(
        val level: HealthLevel = HealthLevel.UNKNOWN,
        val batteryLevel: Int = -1,
        val isBluetoothConnected: Boolean = false,
        val isDatRegistered: Boolean = false,
        val currentFps: Float = 0f,
        val droppedFrames: Long = 0L,
        val lastUpdateMs: Long = 0L,
        val warnings: List<String> = emptyList()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private val _health = MutableStateFlow(ConnectionHealth())
    val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    // Frame tracking
    private var frameCount = 0L
    private var lastFrameCountResetMs = 0L
    private var totalDroppedFrames = 0L

    /**
     * Запускает мониторинг здоровья.
     *
     * @param glassesStateProvider Функция для получения текущего состояния очков.
     * @param btConnectedProvider Функция для проверки BT audio соединения.
     */
    fun start(
        glassesStateProvider: () -> GlassesState,
        btConnectedProvider: () -> Boolean
    ) {
        if (monitorJob?.isActive == true) return

        lastFrameCountResetMs = System.currentTimeMillis()
        frameCount = 0L

        monitorJob = scope.launch {
            Log.d(TAG, "Health monitoring started")
            while (isActive) {
                val health = collectHealth(glassesStateProvider, btConnectedProvider)
                _health.value = health

                if (health.level == HealthLevel.CRITICAL) {
                    Log.w(TAG, "CRITICAL: ${health.warnings.joinToString("; ")}")
                } else if (health.level == HealthLevel.WARNING) {
                    Log.d(TAG, "WARNING: ${health.warnings.joinToString("; ")}")
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Останавливает мониторинг.
     */
    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        _health.value = ConnectionHealth()
        Log.d(TAG, "Health monitoring stopped")
    }

    /**
     * Регистрирует полученный кадр камеры для подсчёта fps.
     */
    fun onFrameReceived() {
        frameCount++
    }

    /**
     * Регистрирует потерянный кадр.
     */
    fun onFrameDropped() {
        totalDroppedFrames++
    }

    private fun collectHealth(
        glassesStateProvider: () -> GlassesState,
        btConnectedProvider: () -> Boolean
    ): ConnectionHealth {
        val now = System.currentTimeMillis()
        val warnings = mutableListOf<String>()

        // Батарея
        datDeviceManager.refreshDeviceInfo()
        val battery = datDeviceManager.state.value.deviceInfo?.batteryLevel ?: -1
        if (battery in 1..BATTERY_CRITICAL_THRESHOLD) {
            warnings.add("Батарея очков критически низкая: $battery%")
        } else if (battery in (BATTERY_CRITICAL_THRESHOLD + 1)..BATTERY_LOW_THRESHOLD) {
            warnings.add("Батарея очков низкая: $battery%")
        }

        // DAT регистрация
        val isDatRegistered = datDeviceManager.state.value.isRegistered
        val glassesState = glassesStateProvider()

        // BT
        val isBtConnected = btConnectedProvider()
        if (glassesState != GlassesState.DISCONNECTED && !isBtConnected && !isDatRegistered) {
            warnings.add("Потеря Bluetooth и DAT соединения")
        }

        // FPS
        val elapsed = (now - lastFrameCountResetMs).coerceAtLeast(1)
        val currentFps = frameCount * 1000f / elapsed
        if (glassesState == GlassesState.STREAMING_AUDIO && currentFps < MIN_ACCEPTABLE_FPS && frameCount > 0) {
            warnings.add("Низкий FPS камеры: ${"%.1f".format(currentFps)}")
        }

        // Сброс счётчика fps каждый poll
        frameCount = 0
        lastFrameCountResetMs = now

        val level = when {
            glassesState == GlassesState.DISCONNECTED -> HealthLevel.UNKNOWN
            warnings.any { "критически" in it || "Потеря" in it } -> HealthLevel.CRITICAL
            warnings.isNotEmpty() -> HealthLevel.WARNING
            else -> HealthLevel.GOOD
        }

        return ConnectionHealth(
            level = level,
            batteryLevel = battery,
            isBluetoothConnected = isBtConnected,
            isDatRegistered = isDatRegistered,
            currentFps = currentFps,
            droppedFrames = totalDroppedFrames,
            lastUpdateMs = now,
            warnings = warnings
        )
    }
}
