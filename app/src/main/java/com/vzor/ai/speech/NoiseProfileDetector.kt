package com.vzor.ai.speech

import com.vzor.ai.domain.model.NoiseProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Detects ambient noise level from audio frames and maps to NoiseProfile.
 * Uses sliding window RMS → dB conversion.
 */
@Singleton
class NoiseProfileDetector @Inject constructor() {

    companion object {
        private const val WINDOW_SIZE = 10
        private const val SHORT_REF = 32768.0 // 16-bit PCM reference
    }

    private val _currentProfile = MutableStateFlow(NoiseProfile.QUIET)
    val currentProfile: StateFlow<NoiseProfile> = _currentProfile.asStateFlow()

    private val _currentDb = MutableStateFlow(0f)
    val currentDb: StateFlow<Float> = _currentDb.asStateFlow()

    private val rmsWindow = ArrayDeque<Double>(WINDOW_SIZE)

    /**
     * Feed raw PCM 16-bit mono audio frame for noise analysis.
     * @param pcmData 16-bit signed PCM samples
     */
    fun processAudioFrame(pcmData: ShortArray) {
        if (pcmData.isEmpty()) return

        val rms = calculateRms(pcmData)
        synchronized(rmsWindow) {
            rmsWindow.addLast(rms)
            if (rmsWindow.size > WINDOW_SIZE) {
                rmsWindow.removeFirst()
            }
        }

        val avgRms = synchronized(rmsWindow) {
            if (rmsWindow.isEmpty()) 0.0 else rmsWindow.average()
        }
        val db = rmsToDb(avgRms).toFloat()

        _currentDb.value = db
        _currentProfile.value = NoiseProfile.fromDbLevel(db)
    }

    /**
     * Feed raw PCM 16-bit mono audio as ByteArray for noise analysis.
     * Converts to ShortArray and delegates to [processAudioFrame].
     */
    fun updateFromAudio(pcmData: ByteArray) {
        if (pcmData.size < 2) return
        val shorts = ShortArray(pcmData.size / 2)
        for (i in shorts.indices) {
            val low = pcmData[i * 2].toInt() and 0xFF
            val high = pcmData[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        processAudioFrame(shorts)
    }

    fun reset() {
        synchronized(rmsWindow) { rmsWindow.clear() }
        _currentDb.value = 0f
        _currentProfile.value = NoiseProfile.QUIET
    }

    private fun calculateRms(samples: ShortArray): Double {
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / SHORT_REF
            sum += normalized * normalized
        }
        return sqrt(sum / samples.size)
    }

    private fun rmsToDb(rms: Double): Double {
        if (rms <= 0.0) return 0.0
        // Convert to approximate dB SPL (calibrated for typical phone mic)
        return 20.0 * log10(rms) + 90.0 // +90 offset to map to ~dB SPL range
    }
}
