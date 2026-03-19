package com.vzor.ai.speech

import android.util.Log
import kotlin.math.sqrt

/**
 * Fallback wake word engine на основе energy analysis.
 *
 * Используется когда Picovoice Access Key не указан.
 * Анализирует энергетический профиль и zero-crossing rate
 * для обнаружения слова "Взор":
 * - Длительность: 300-1200ms
 * - Energy profile: onset (В) + sustained mid (зо) + falling (р)
 * - High ZCR на onset (фрикативный "В")
 *
 * Высокий false positive rate — STT подтверждает через транскрипт.
 */
class EnergyWakeWordEngine : WakeWordEngine {

    companion object {
        private const val TAG = "EnergyWakeWordEngine"
        private const val SAMPLE_RATE = 16_000
    }

    private var _isReady = true
    private val segmentBuffer = mutableListOf<ShortArray>()
    private var isInSpeech = false
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0

    override fun process(pcmData: ShortArray): Boolean {
        if (pcmData.isEmpty()) return false

        val rmsDb = calculateRmsDb(pcmData)
        val isSpeech = rmsDb > -35f

        if (isSpeech) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0
            if (consecutiveSpeechFrames >= 5) {
                if (!isInSpeech) {
                    isInSpeech = true
                    segmentBuffer.clear()
                }
                segmentBuffer.add(pcmData.copyOf())
                if (segmentBuffer.size > 150) {
                    resetState()
                }
            }
        } else {
            consecutiveSilenceFrames++
            consecutiveSpeechFrames = 0
            if (isInSpeech && consecutiveSilenceFrames >= 15) {
                val detected = analyzeSegment()
                resetState()
                return detected
            }
        }

        return false
    }

    override fun release() {
        _isReady = false
        segmentBuffer.clear()
    }

    override fun isReady(): Boolean = _isReady

    private fun analyzeSegment(): Boolean {
        if (segmentBuffer.isEmpty()) return false

        val allSamples = segmentBuffer.flatMap { it.toList() }.toShortArray()
        val sampleCount = allSamples.size
        if (sampleCount == 0) return false

        val durationMs = (sampleCount * 1000) / SAMPLE_RATE
        if (durationMs < 300 || durationMs > 1200) return false

        val thirdSize = sampleCount / 3
        val onsetRms = calculateRmsForRange(allSamples, 0, thirdSize)
        val middleRms = calculateRmsForRange(allSamples, thirdSize, thirdSize * 2)
        val offsetRms = calculateRmsForRange(allSamples, thirdSize * 2, sampleCount)

        val overallRmsDb = calculateRmsDb(allSamples)
        if (overallRmsDb < -30f) return false

        val hasExpectedPattern = middleRms >= onsetRms * 0.7 &&
            middleRms >= offsetRms * 0.7 &&
            onsetRms > 500.0

        if (!hasExpectedPattern) return false

        val onsetZcr = calculateZeroCrossingRate(allSamples, 0, thirdSize)
        val hasHighOnsetZcr = onsetZcr > 0.15f

        if (hasExpectedPattern && hasHighOnsetZcr) {
            Log.d(TAG, "Potential wake word detected (energy heuristic, " +
                "duration=${durationMs}ms, zcr=${"%.2f".format(onsetZcr)})")
            return true
        }

        return false
    }

    private fun resetState() {
        isInSpeech = false
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        segmentBuffer.clear()
    }

    private fun calculateRmsDb(samples: ShortArray): Float {
        if (samples.isEmpty()) return -90f
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample.toDouble() * sample)
        }
        val rms = sqrt(sumSquares / samples.size)
        return if (rms < 1.0) -90f
        else (20.0 * kotlin.math.log10(rms / Short.MAX_VALUE)).toFloat()
    }

    private fun calculateRmsForRange(samples: ShortArray, start: Int, end: Int): Double {
        val count = end - start
        if (count <= 0) return 0.0
        var sumSquares = 0.0
        for (i in start until minOf(end, samples.size)) {
            sumSquares += (samples[i].toDouble() * samples[i])
        }
        return sqrt(sumSquares / count)
    }

    private fun calculateZeroCrossingRate(samples: ShortArray, start: Int, end: Int): Float {
        val count = end - start
        if (count <= 1) return 0f
        var crossings = 0
        for (i in start + 1 until minOf(end, samples.size)) {
            if ((samples[i - 1] >= 0 && samples[i] < 0) || (samples[i - 1] < 0 && samples[i] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (count - 1)
    }
}
