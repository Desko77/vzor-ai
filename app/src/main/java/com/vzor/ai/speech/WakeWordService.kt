package com.vzor.ai.speech

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Listener interface for wake word detection events.
 */
interface WakeWordListener {
    /** Called on the main thread when the wake word "Взор" is detected. */
    fun onWakeWordDetected()
}

/**
 * Wake word detection service for "Взор" (Vzor).
 *
 * Architecture:
 * - MVP implementation uses energy-based Voice Activity Detection (VAD) to
 *   identify speech segments, then flags them for the STT service to verify
 *   whether the transcript contains the wake word "взор".
 * - Production implementation: plug in Porcupine SDK with a custom keyword
 *   model trained on "Взор". The [WakeWordEngine] interface allows swapping
 *   the detection backend without changing the service API.
 *
 * Detection pipeline:
 * 1. Receive PCM 16kHz audio chunks from [AudioStreamHandler]
 * 2. Calculate frame energy (RMS)
 * 3. If energy exceeds speech threshold for [MIN_SPEECH_FRAMES] consecutive frames →
 *    accumulate audio into a speech segment buffer
 * 4. When energy drops below threshold for [SILENCE_FRAMES_TO_END] frames →
 *    speech segment complete
 * 5. Run keyword detection on the accumulated segment
 * 6. If keyword detected → notify [WakeWordListener]
 */
@Singleton
class WakeWordService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "WakeWordService"

        /**
         * RMS dB threshold to consider a frame as containing speech.
         * Typical quiet room: -40 to -30 dB. Speech: -25 to -5 dB.
         */
        private const val SPEECH_THRESHOLD_DB = -35f

        /**
         * Minimum consecutive speech frames to start accumulating a segment.
         * At 20ms per frame, 5 frames = 100ms — filters out transient noise.
         */
        private const val MIN_SPEECH_FRAMES = 5

        /**
         * Number of consecutive silence frames to mark end of speech segment.
         * At 20ms per frame, 15 frames = 300ms of silence.
         */
        private const val SILENCE_FRAMES_TO_END = 15

        /**
         * Maximum speech segment duration in frames before forced flush.
         * At 20ms per frame, 150 frames = 3 seconds — "взор" is well under this.
         */
        private const val MAX_SEGMENT_FRAMES = 150

        /**
         * Minimum segment size (in bytes) to consider for keyword detection.
         * "Взор" is roughly 400–600ms → ~12800–19200 bytes at 16kHz/16-bit.
         */
        private const val MIN_SEGMENT_BYTES = 6400 // ~200ms

        /**
         * Maximum segment size to consider for keyword detection.
         * Keeps memory bounded; longer segments are unlikely to be just a wake word.
         */
        private const val MAX_SEGMENT_BYTES = 64000 // ~2 seconds
    }

    private var listener: WakeWordListener? = null
    @Volatile var isListening: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null

    // VAD state
    private var consecutiveSpeechFrames = 0
    private var consecutiveSilenceFrames = 0
    private var isInSpeechSegment = false
    private var segmentBuffer = mutableListOf<ByteArray>()
    private var segmentFrameCount = 0

    /**
     * Start listening for the wake word on the given audio stream.
     *
     * The audio stream should provide PCM 16kHz mono 16-bit chunks (typically 640 bytes / 20ms).
     * Detection runs on [Dispatchers.Default] to avoid blocking the IO thread.
     *
     * @param audioStream Flow of PCM audio chunks from [AudioStreamHandler.startCapture]
     */
    fun startListening(audioStream: Flow<ByteArray>) {
        if (isListening) {
            Log.d(TAG, "Already listening for wake word")
            return
        }

        isListening = true
        resetVadState()

        listeningJob = scope.launch {
            Log.d(TAG, "Wake word detection started")

            audioStream.collect { chunk ->
                if (!isListening) return@collect
                processAudioChunk(chunk)
            }

            Log.d(TAG, "Audio stream ended, wake word detection stopped")
            isListening = false
        }
    }

    /**
     * Stop listening for the wake word.
     */
    fun stopListening() {
        Log.d(TAG, "Stopping wake word detection")
        isListening = false
        listeningJob?.cancel()
        listeningJob = null
        resetVadState()
    }

    /**
     * Set the listener to be notified when the wake word is detected.
     */
    fun setListener(listener: WakeWordListener) {
        this.listener = listener
    }

    /**
     * Remove the current listener.
     */
    fun clearListener() {
        this.listener = null
    }

    // -----------------------------------------------------------------
    // Internal VAD + keyword detection pipeline
    // -----------------------------------------------------------------

    private fun processAudioChunk(chunk: ByteArray) {
        val rmsDb = calculateRmsDb(chunk)
        val isSpeech = rmsDb > SPEECH_THRESHOLD_DB

        if (isSpeech) {
            consecutiveSpeechFrames++
            consecutiveSilenceFrames = 0

            if (consecutiveSpeechFrames >= MIN_SPEECH_FRAMES) {
                if (!isInSpeechSegment) {
                    isInSpeechSegment = true
                    segmentBuffer.clear()
                    segmentFrameCount = 0
                    Log.v(TAG, "Speech segment started (rmsDb=%.1f)".format(rmsDb))
                }

                segmentBuffer.add(chunk.copyOf())
                segmentFrameCount++

                // Force flush if segment is too long (not a wake word)
                if (segmentFrameCount >= MAX_SEGMENT_FRAMES) {
                    Log.v(TAG, "Segment too long ($segmentFrameCount frames), discarding")
                    resetVadState()
                }
            }
        } else {
            consecutiveSilenceFrames++
            consecutiveSpeechFrames = 0

            if (isInSpeechSegment) {
                // Still accumulate during short silence gaps within speech
                segmentBuffer.add(chunk.copyOf())
                segmentFrameCount++

                if (consecutiveSilenceFrames >= SILENCE_FRAMES_TO_END) {
                    // Speech segment complete — run keyword detection
                    onSpeechSegmentComplete()
                    resetVadState()
                }
            }
        }
    }

    private fun onSpeechSegmentComplete() {
        val totalBytes = segmentBuffer.sumOf { it.size }
        Log.v(TAG, "Speech segment complete: $segmentFrameCount frames, $totalBytes bytes")

        if (totalBytes < MIN_SEGMENT_BYTES) {
            Log.v(TAG, "Segment too short ($totalBytes bytes), skipping keyword detection")
            return
        }

        if (totalBytes > MAX_SEGMENT_BYTES) {
            Log.v(TAG, "Segment too long ($totalBytes bytes), skipping keyword detection")
            return
        }

        // Merge chunks into a single buffer for analysis
        val segmentData = ByteArray(totalBytes)
        var offset = 0
        for (chunk in segmentBuffer) {
            chunk.copyInto(segmentData, offset)
            offset += chunk.size
        }

        if (detectWakeWord(segmentData)) {
            Log.i(TAG, "Wake word 'Взор' detected!")
            listener?.onWakeWordDetected()
        }
    }

    /**
     * Keyword detection on a speech segment.
     *
     * MVP approach: Energy-based heuristic detection.
     * The method analyzes the energy profile of the segment to determine if it
     * matches the expected pattern for the word "Взор":
     * - Duration: 400–800ms (short, punchy word)
     * - Energy profile: rising onset (В), sustained mid (зо), falling offset (р)
     * - Overall energy should be strong (intentional utterance, not background speech)
     *
     * When this returns true, the segment is flagged and the VoiceOrchestrator will
     * send it to STT for transcript-level confirmation of "взор".
     *
     * Production replacement: Porcupine SDK with custom "Взор" model:
     *   val porcupine = Porcupine.Builder()
     *       .setAccessKey(accessKey)
     *       .setKeywordPath("vzor_ru.ppn")
     *       .build()
     *   val keywordIndex = porcupine.process(shortArray)
     *   return keywordIndex >= 0
     */
    private fun detectWakeWord(segmentData: ByteArray): Boolean {
        val sampleCount = segmentData.size / 2
        if (sampleCount == 0) return false

        // Calculate segment duration in ms
        val durationMs = (sampleCount * 1000) / 16000

        // "Взор" should be between 300ms and 1200ms
        if (durationMs < 300 || durationMs > 1200) {
            return false
        }

        // Analyze energy profile in 3 segments (onset, middle, offset)
        val thirdSize = sampleCount / 3

        val onsetRms = calculateRmsForRange(segmentData, 0, thirdSize)
        val middleRms = calculateRmsForRange(segmentData, thirdSize, thirdSize * 2)
        val offsetRms = calculateRmsForRange(segmentData, thirdSize * 2, sampleCount)

        // Overall energy must be above speech level
        val overallRms = calculateRmsDb(segmentData)
        if (overallRms < -30f) {
            return false
        }

        // "Взор" energy pattern: onset should have significant energy (fricative "В"),
        // middle should be strongest (vowel "зо"), offset tapers ("р")
        val hasExpectedPattern = middleRms >= onsetRms * 0.7 &&
            middleRms >= offsetRms * 0.7 &&
            onsetRms > 500.0 // Minimum onset energy for fricative "В"

        if (!hasExpectedPattern) {
            return false
        }

        // Check for zero-crossing rate in onset (fricative "В" has high ZCR)
        val onsetZcr = calculateZeroCrossingRate(segmentData, 0, thirdSize)
        val hasHighOnsetZcr = onsetZcr > 0.15f // Fricatives typically > 0.15

        // For MVP: flag as potential wake word if energy pattern and ZCR match.
        // False positives are acceptable — STT will confirm.
        return hasExpectedPattern && hasHighOnsetZcr
    }

    /**
     * Calculate RMS amplitude for a range of samples in a PCM 16-bit buffer.
     */
    private fun calculateRmsForRange(data: ByteArray, startSample: Int, endSample: Int): Double {
        var sumSquares = 0.0
        val count = endSample - startSample
        if (count <= 0) return 0.0

        for (i in startSample until endSample) {
            val byteIndex = i * 2
            if (byteIndex + 1 >= data.size) break
            val low = data[byteIndex].toInt() and 0xFF
            val high = data[byteIndex + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
        }

        return sqrt(sumSquares / count)
    }

    /**
     * Calculate zero-crossing rate for a range of samples.
     * ZCR is the rate at which the signal changes sign, useful for
     * distinguishing voiced (low ZCR) vs unvoiced/fricative (high ZCR) sounds.
     */
    private fun calculateZeroCrossingRate(data: ByteArray, startSample: Int, endSample: Int): Float {
        var crossings = 0
        val count = endSample - startSample
        if (count <= 1) return 0f

        var prevSample = getSample(data, startSample)
        for (i in startSample + 1 until endSample) {
            val sample = getSample(data, i)
            if ((prevSample >= 0 && sample < 0) || (prevSample < 0 && sample >= 0)) {
                crossings++
            }
            prevSample = sample
        }

        return crossings.toFloat() / (count - 1)
    }

    /**
     * Extract a 16-bit signed sample from PCM byte array at the given sample index.
     */
    private fun getSample(data: ByteArray, sampleIndex: Int): Int {
        val byteIndex = sampleIndex * 2
        if (byteIndex + 1 >= data.size) return 0
        val low = data[byteIndex].toInt() and 0xFF
        val high = data[byteIndex + 1].toInt()
        return (high shl 8) or low
    }

    /**
     * Calculate RMS level in dB for a PCM 16-bit audio buffer.
     */
    private fun calculateRmsDb(data: ByteArray): Float {
        if (data.size < 2) return -90f

        val sampleCount = data.size / 2
        var sumSquares = 0.0

        for (i in 0 until sampleCount) {
            val low = data[i * 2].toInt() and 0xFF
            val high = data[i * 2 + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample * sample).toDouble()
        }

        val rms = sqrt(sumSquares / sampleCount)
        return if (rms < 1.0) {
            -90f
        } else {
            (20.0 * kotlin.math.log10(rms / Short.MAX_VALUE)).toFloat()
        }
    }

    private fun resetVadState() {
        consecutiveSpeechFrames = 0
        consecutiveSilenceFrames = 0
        isInSpeechSegment = false
        segmentBuffer.clear()
        segmentFrameCount = 0
    }
}
