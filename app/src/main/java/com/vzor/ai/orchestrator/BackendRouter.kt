package com.vzor.ai.orchestrator

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.NetworkType
import com.vzor.ai.domain.model.RoutingContext
import com.vzor.ai.domain.model.RoutingDecision
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Determines which AI backend to use based on network conditions,
 * battery level, and EVO X2 local server availability.
 *
 * Routing algorithm priority:
 * 1. Offline → on-device model (Qwen3.5-4B)
 * 2. Battery < 20% → cloud (minimize local AI load)
 * 3. Wi-Fi + X2 unavailable → cloud
 * 4. Wi-Fi + X2 available + queue < 800ms → local AI
 * 5. Wi-Fi + X2 available + queue >= 800ms → cloud (X2 overloaded)
 * 6. LTE → cloud
 */
@Singleton
class BackendRouter @Inject constructor(
    private val prefs: PreferencesManager
) {
    companion object {
        /** Maximum acceptable queue wait time on EVO X2 before falling back to cloud. */
        private const val X2_QUEUE_THRESHOLD_MS = 800L

        /** Battery level below which we prefer cloud to save device resources. */
        private const val LOW_BATTERY_THRESHOLD = 20
    }

    /**
     * Decide the routing target for the current request.
     *
     * @param context Current device and network conditions.
     * @return The [RoutingDecision] indicating which backend to use.
     */
    fun route(context: RoutingContext): RoutingDecision {
        // 1. Offline → on-device offline backend
        if (context.networkType == NetworkType.OFFLINE) {
            return RoutingDecision.OFFLINE
        }

        // 2. Battery < 20% → cloud (minimize local AI load)
        if (context.batteryLevel < LOW_BATTERY_THRESHOLD) {
            return RoutingDecision.CLOUD
        }

        // 3. Wi-Fi but X2 unavailable → cloud
        if (context.networkType == NetworkType.WIFI && !context.x2Available) {
            return RoutingDecision.CLOUD
        }

        // 4. Wi-Fi, X2 available, queue ok → local AI
        if (context.networkType == NetworkType.WIFI &&
            context.x2Available &&
            context.x2QueueWaitMs < X2_QUEUE_THRESHOLD_MS
        ) {
            return RoutingDecision.LOCAL_AI
        }

        // 5. Wi-Fi, X2 available but overloaded → cloud
        if (context.networkType == NetworkType.WIFI &&
            context.x2Available &&
            context.x2QueueWaitMs >= X2_QUEUE_THRESHOLD_MS
        ) {
            return RoutingDecision.CLOUD
        }

        // 6. LTE or any other case → cloud
        return RoutingDecision.CLOUD
    }
}
