package com.vzor.ai.orchestrator

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Мониторинг батареи для маршрутизации AI-запросов.
 *
 * BackendRouter использует уровень заряда для решений:
 * - < 20% → предпочитаем cloud (снижаем нагрузку на устройство)
 * - < 10% → агрессивное энергосбережение (отключаем live commentary и т.д.)
 * - Charging → можно использовать on-device модели без ограничений
 *
 * Также отслеживает температуру батареи для thermal throttling.
 */
@Singleton
class BatteryMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "BatteryMonitor"

        /** Порог низкого заряда: предпочитаем cloud. */
        const val LOW_BATTERY_THRESHOLD = 20

        /** Критический заряд: ограничиваем функции. */
        const val CRITICAL_BATTERY_THRESHOLD = 10

        /** Порог температуры для thermal throttling (в десятых °C). */
        private const val THERMAL_THROTTLE_TEMP = 420 // 42.0°C
    }

    /**
     * Состояние батареи устройства.
     */
    data class BatteryState(
        val level: Int = 100,
        val isCharging: Boolean = false,
        val temperature: Float = 25.0f,
        val isLow: Boolean = false,
        val isCritical: Boolean = false,
        val isThermalThrottle: Boolean = false
    )

    private val _state = MutableStateFlow(BatteryState())

    /** Текущее состояние батареи (обновляется при изменениях). */
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    /** Текущий уровень заряда (0-100). */
    val level: Int get() = _state.value.level

    /** Заряжается ли устройство. */
    val isCharging: Boolean get() = _state.value.isCharging

    /** Батарея ниже LOW_BATTERY_THRESHOLD. */
    val isLow: Boolean get() = _state.value.isLow

    /** Батарея ниже CRITICAL_BATTERY_THRESHOLD. */
    val isCritical: Boolean get() = _state.value.isCritical

    /** Устройство перегревается. */
    val isThermalThrottle: Boolean get() = _state.value.isThermalThrottle

    private var batteryReceiver: BroadcastReceiver? = null

    /**
     * Начинает мониторинг батареи.
     * Вызывается при старте приложения.
     */
    fun startMonitoring() {
        // Sticky broadcast: получаем текущее состояние сразу
        val stickyIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        stickyIntent?.let { updateFromIntent(it) }

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                updateFromIntent(intent)
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        context.registerReceiver(batteryReceiver, filter)
        Log.d(TAG, "Battery monitoring started")
    }

    /**
     * Останавливает мониторинг батареи.
     */
    fun stopMonitoring() {
        batteryReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: IllegalArgumentException) {
                // Receiver не был зарегистрирован
            }
        }
        batteryReceiver = null
        Log.d(TAG, "Battery monitoring stopped")
    }

    /**
     * Возвращает текущее состояние через sticky broadcast (без регистрации).
     */
    fun getCurrentState(): BatteryState {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.let { parseIntent(it) } ?: _state.value
    }

    private fun updateFromIntent(intent: Intent) {
        val newState = parseIntent(intent)
        val oldState = _state.value
        _state.value = newState

        if (oldState.level != newState.level || oldState.isCharging != newState.isCharging) {
            Log.d(TAG, "Battery: ${newState.level}%, charging=${newState.isCharging}, " +
                "temp=${newState.temperature}°C, low=${newState.isLow}")
        }
    }

    private fun parseIntent(intent: Intent): BatteryState {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else 100

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
        val temperature = tempTenths / 10.0f

        return BatteryState(
            level = percent,
            isCharging = isCharging,
            temperature = temperature,
            isLow = percent < LOW_BATTERY_THRESHOLD,
            isCritical = percent < CRITICAL_BATTERY_THRESHOLD,
            isThermalThrottle = tempTenths > THERMAL_THROTTLE_TEMP
        )
    }
}
