package com.vzor.ai.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Error entry recorded by the telemetry system.
 */
data class TelemetryError(
    val component: String,
    val error: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Snapshot of all telemetry metrics at a point in time.
 */
data class TelemetryReport(
    val avgLatencies: Map<String, Long>,
    val cacheHitRate: Float,
    val fallbackCount: Int,
    val bargeInCount: Int,
    val errors: List<TelemetryError>
)

/**
 * Tracks performance metrics across the application.
 *
 * All operations are thread-safe. Latency values are stored in circular buffers
 * (last 100 per operation) to prevent unbounded memory growth.
 */
@Singleton
class TelemetryTracker @Inject constructor() {

    companion object {
        private const val MAX_LATENCY_ENTRIES = 100
        private const val MAX_ERROR_ENTRIES = 100
    }

    // --- Latency tracking ---
    private val latencyBuffers = ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>>()
    private val _avgLatency = MutableStateFlow<Map<String, Long>>(emptyMap())
    val avgLatency: StateFlow<Map<String, Long>> = _avgLatency.asStateFlow()

    // --- Cache hit/miss tracking ---
    private val cacheHits = AtomicInteger(0)
    private val cacheMisses = AtomicInteger(0)
    private val _cacheHitRate = MutableStateFlow(0f)
    val cacheHitRate: StateFlow<Float> = _cacheHitRate.asStateFlow()

    // --- Fallback tracking ---
    private val _fallbackCount = MutableStateFlow(0)
    val fallbackCount: StateFlow<Int> = _fallbackCount.asStateFlow()
    private val fallbackCounter = AtomicInteger(0)
    private val fallbackDetails = ConcurrentLinkedDeque<Pair<String, String>>()

    // --- Barge-in tracking ---
    private val bargeInCounter = AtomicInteger(0)

    // --- Error tracking ---
    private val errorBuffer = ConcurrentLinkedDeque<TelemetryError>()
    private val _recentErrors = MutableStateFlow<List<TelemetryError>>(emptyList())
    val recentErrors: StateFlow<List<TelemetryError>> = _recentErrors.asStateFlow()

    /**
     * Record a latency measurement for the given operation.
     * Stores in a circular buffer of the last [MAX_LATENCY_ENTRIES] values
     * and recalculates the running average.
     */
    fun recordLatency(operation: String, durationMs: Long) {
        val buffer = latencyBuffers.getOrPut(operation) { ConcurrentLinkedDeque() }

        buffer.addLast(durationMs)

        // Trim to circular buffer size
        while (buffer.size > MAX_LATENCY_ENTRIES) {
            buffer.pollFirst()
        }

        // Recalculate averages for all operations
        updateAverageLatencies()
    }

    /**
     * Record a cache hit or miss. Updates the running hit rate percentage.
     */
    fun recordCacheHit(hit: Boolean) {
        if (hit) {
            cacheHits.incrementAndGet()
        } else {
            cacheMisses.incrementAndGet()
        }
        updateCacheHitRate()
    }

    /**
     * Record a fallback event from one provider/service to another.
     */
    fun recordFallback(from: String, to: String) {
        fallbackCounter.incrementAndGet()
        fallbackDetails.addLast(from to to)

        // Keep fallback details bounded
        while (fallbackDetails.size > MAX_LATENCY_ENTRIES) {
            fallbackDetails.pollFirst()
        }

        _fallbackCount.value = fallbackCounter.get()
    }

    /**
     * Record a barge-in event (user interrupted the assistant while it was speaking).
     */
    fun recordBargeIn() {
        bargeInCounter.incrementAndGet()
    }

    /**
     * Record an error from a specific component.
     */
    fun recordError(component: String, error: String) {
        val entry = TelemetryError(
            component = component,
            error = error,
            timestamp = System.currentTimeMillis()
        )

        errorBuffer.addLast(entry)

        // Trim to max size
        while (errorBuffer.size > MAX_ERROR_ENTRIES) {
            errorBuffer.pollFirst()
        }

        _recentErrors.value = errorBuffer.toList()
    }

    /**
     * Generate a snapshot report of all current telemetry metrics.
     */
    fun getReport(): TelemetryReport {
        return TelemetryReport(
            avgLatencies = computeAverageLatencies(),
            cacheHitRate = computeCacheHitRate(),
            fallbackCount = fallbackCounter.get(),
            bargeInCount = bargeInCounter.get(),
            errors = errorBuffer.toList()
        )
    }

    // --- Internal helpers ---

    private fun updateAverageLatencies() {
        _avgLatency.value = computeAverageLatencies()
    }

    private fun computeAverageLatencies(): Map<String, Long> {
        return latencyBuffers.mapValues { (_, buffer) ->
            val values = buffer.toList()
            if (values.isEmpty()) 0L else values.sum() / values.size
        }
    }

    private fun updateCacheHitRate() {
        _cacheHitRate.value = computeCacheHitRate()
    }

    private fun computeCacheHitRate(): Float {
        val hits = cacheHits.get()
        val misses = cacheMisses.get()
        val total = hits + misses
        return if (total == 0) 0f else hits.toFloat() / total.toFloat()
    }
}
