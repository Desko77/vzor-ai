package com.vzor.ai.orchestrator

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.NetworkType
import com.vzor.ai.domain.model.RoutingContext
import com.vzor.ai.domain.model.RoutingDecision
import com.vzor.ai.speech.AudioContext
import com.vzor.ai.speech.AudioContextDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Determines which AI backend to use based on network conditions,
 * battery level, connection profile, audio context and EVO X2 local server availability.
 *
 * Routing algorithm priority:
 * 1. Offline → on-device model (Qwen3.5-4B)
 * 2. Battery < 20% → cloud (minimize local AI load)
 * 3. HOME_WIFI profile + X2 available + queue ok → local AI (auto-switch)
 * 4. Wi-Fi + X2 unavailable → cloud
 * 5. Wi-Fi + X2 available + queue < 800ms → local AI
 * 6. Wi-Fi + X2 available + queue >= 800ms → cloud (X2 overloaded)
 * 7. LTE → cloud
 *
 * Дополнительно: AudioContextDetector влияет на приоритет STT:
 * - MUSIC → подавление STT (снижение false-positive от wake word)
 * - SPEECH → повышенный приоритет STT
 */
@Singleton
class BackendRouter @Inject constructor(
    private val prefs: PreferencesManager,
    private val connectionProfileManager: ConnectionProfileManager? = null,
    private val audioContextDetector: AudioContextDetector? = null,
    private val modelRuntimeManager: ModelRuntimeManager? = null
) {
    companion object {
        /** Maximum acceptable queue wait time on EVO X2 before falling back to cloud. */
        private const val X2_QUEUE_THRESHOLD_MS = 800L

        /** Battery level below which we prefer cloud to save device resources. */
        private const val LOW_BATTERY_THRESHOLD = 20

        /** Порог использования памяти X2 (%), при котором переходим на cloud. */
        private const val X2_MEMORY_PRESSURE_THRESHOLD = 0.9f
    }

    /**
     * Decide the routing target for the current request.
     *
     * @param context Current device and network conditions.
     * @return The [RoutingDecision] indicating which backend to use.
     */
    /** Текущий профиль подключения (для UI/логирования). */
    val currentProfile: ConnectionProfile
        get() = connectionProfileManager?.currentProfile?.value ?: ConnectionProfile.OFFLINE

    /** Текущий аудио-контекст (для UI/логирования). */
    val audioContext: AudioContext
        get() = audioContextDetector?.currentContext?.value ?: AudioContext.SILENCE

    /**
     * Следует ли подавлять STT-распознавание (например, при воспроизведении музыки).
     * Позволяет избежать ложных wake word detections во время прослушивания.
     */
    val shouldSuppressStt: Boolean
        get() = audioContextDetector?.currentContext?.value == AudioContext.MUSIC

    /** Загруженные модели на X2 (для UI/диагностики). */
    val loadedModels: List<ModelSlot>
        get() = modelRuntimeManager?.getLoadedModels() ?: emptyList()

    /** Используемая память на X2 (MB). */
    val x2UsedMemoryMb: Int
        get() = modelRuntimeManager?.usedMemoryMb?.value ?: 0

    /**
     * X2 под давлением памяти — если загружено > 90% лимита,
     * предпочитаем cloud для снижения нагрузки.
     */
    private val isX2MemoryPressure: Boolean
        get() {
            val manager = modelRuntimeManager ?: return false
            val used = manager.usedMemoryMb.value
            val limit = manager.totalMemoryMb?.value ?: return false
            if (limit <= 0) return false
            return used.toFloat() / limit > X2_MEMORY_PRESSURE_THRESHOLD
        }

    fun route(context: RoutingContext): RoutingDecision {
        // 1. Offline → on-device offline backend
        if (context.networkType == NetworkType.OFFLINE) {
            return RoutingDecision.OFFLINE
        }

        // 2. Battery < 20% → cloud (minimize local AI load)
        if (context.batteryLevel < LOW_BATTERY_THRESHOLD) {
            return RoutingDecision.CLOUD
        }

        // 3. X2 memory pressure → cloud (даже если доступен, чтобы не усугубить)
        if (context.x2Available && isX2MemoryPressure) {
            return RoutingDecision.CLOUD
        }

        // 4. HOME_WIFI + X2 available → предпочитаем local AI (автопереключение)
        val profile = connectionProfileManager?.currentProfile?.value
        if (profile == ConnectionProfile.HOME_WIFI &&
            context.x2Available &&
            context.x2QueueWaitMs < X2_QUEUE_THRESHOLD_MS
        ) {
            return RoutingDecision.LOCAL_AI
        }

        // 5. Wi-Fi but X2 unavailable → cloud
        if (context.networkType == NetworkType.WIFI && !context.x2Available) {
            return RoutingDecision.CLOUD
        }

        // 6. Wi-Fi, X2 available, queue ok → local AI
        if (context.networkType == NetworkType.WIFI &&
            context.x2Available &&
            context.x2QueueWaitMs < X2_QUEUE_THRESHOLD_MS
        ) {
            return RoutingDecision.LOCAL_AI
        }

        // 7. Wi-Fi, X2 available but overloaded → cloud
        if (context.networkType == NetworkType.WIFI &&
            context.x2Available &&
            context.x2QueueWaitMs >= X2_QUEUE_THRESHOLD_MS
        ) {
            return RoutingDecision.CLOUD
        }

        // 8. LTE or any other case → cloud
        return RoutingDecision.CLOUD
    }
}
