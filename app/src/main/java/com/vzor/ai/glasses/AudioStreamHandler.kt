package com.vzor.ai.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.vzor.ai.domain.model.GlassesState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Handles audio capture from BT HFP microphone (glasses) with automatic
 * fallback to the device's built-in microphone.
 *
 * Audio format: PCM 16kHz mono 16-bit (suitable for Whisper, VAD, wake-word detection).
 *
 * Usage:
 *   val audioFlow = audioStreamHandler.startCapture()
 *   audioFlow.collect { pcmChunk ->
 *       // Process 16kHz PCM chunk (typically 320-640 bytes per frame)
 *   }
 */
@Singleton
class AudioStreamHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glassesManager: GlassesManager
) {
    companion object {
        private const val TAG = "AudioStreamHandler"

        /** Target sample rate for all downstream consumers (STT, VAD, wake-word). */
        const val SAMPLE_RATE = 16_000

        /** Mono input — glasses mic and most device mics. */
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** 16-bit PCM — standard for speech processing pipelines. */
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Duration of each audio chunk in milliseconds. */
        private const val CHUNK_DURATION_MS = 20

        /** Number of samples per chunk: 16000 * 20/1000 = 320 samples = 640 bytes. */
        private const val SAMPLES_PER_CHUNK = SAMPLE_RATE * CHUNK_DURATION_MS / 1000

        /** Bytes per chunk (16-bit = 2 bytes per sample). */
        private const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2

        /** Minimum RMS dB considered as silence. */
        private const val SILENCE_THRESHOLD_DB = -50f
    }

    val sampleRate: Int = SAMPLE_RATE
    val channelConfig: Int = CHANNEL_CONFIG
    val audioFormat: Int = AUDIO_FORMAT

    private var audioRecord: AudioRecord? = null
    @Volatile var isCapturing: Boolean = false
        private set

    /**
     * Start capturing audio and return a [Flow] of PCM byte arrays.
     *
     * Source selection:
     * 1. If glasses are connected and BT SCO is active → uses BT HFP mic via
     *    [MediaRecorder.AudioSource.VOICE_COMMUNICATION]
     * 2. Otherwise → uses device mic via [MediaRecorder.AudioSource.MIC]
     *
     * Each emitted [ByteArray] is a 20ms audio chunk (640 bytes at 16kHz/16-bit/mono).
     * The flow completes when [stopCapture] is called or the coroutine is cancelled.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(): Flow<ByteArray> = callbackFlow {
        val useBluetoothMic = glassesManager.state.value == GlassesState.STREAMING_AUDIO ||
            glassesManager.isBluetoothAudioConnected()

        val audioSource = if (useBluetoothMic) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBufferSize")
            close(IllegalStateException("Cannot determine audio buffer size"))
            return@callbackFlow
        }

        // Use a buffer at least 4x the chunk size for smooth recording
        val bufferSize = maxOf(minBufferSize, BYTES_PER_CHUNK * 4)

        val record = try {
            AudioRecord(audioSource, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            close(e)
            return@callbackFlow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            close(e)
            return@callbackFlow
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize (state=${record.state})")
            record.release()
            close(IllegalStateException("AudioRecord not initialized"))
            return@callbackFlow
        }

        // Route to Bluetooth SCO device if available
        if (useBluetoothMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val scoDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (scoDevices.isNotEmpty()) {
                record.setPreferredDevice(scoDevices[0])
                Log.d(TAG, "Routing audio input to BT SCO device: ${scoDevices[0].productName}")
            } else {
                Log.w(TAG, "BT SCO requested but no SCO input device found, falling back to default mic")
            }
        }

        audioRecord = record
        isCapturing = true

        Log.d(TAG, "Starting audio capture (source=${if (useBluetoothMic) "BT_HFP" else "MIC"}, " +
            "bufferSize=$bufferSize, chunkSize=$BYTES_PER_CHUNK)")

        try {
            record.startRecording()

            val readBuffer = ByteArray(BYTES_PER_CHUNK)

            while (isActive && isCapturing) {
                val bytesRead = record.read(readBuffer, 0, BYTES_PER_CHUNK)

                when {
                    bytesRead > 0 -> {
                        // Copy the buffer so downstream consumers get an independent array
                        val chunk = readBuffer.copyOf(bytesRead)
                        trySend(chunk)

                        // Also emit to GlassesManager's shared flow for other subscribers
                        glassesManager.emitAudioFrame(chunk)
                    }
                    bytesRead == AudioRecord.ERROR_INVALID_OPERATION -> {
                        Log.e(TAG, "AudioRecord.ERROR_INVALID_OPERATION")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_BAD_VALUE -> {
                        Log.e(TAG, "AudioRecord.ERROR_BAD_VALUE")
                        break
                    }
                    bytesRead == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log.e(TAG, "AudioRecord.ERROR_DEAD_OBJECT — device disconnected?")
                        break
                    }
                    bytesRead == AudioRecord.ERROR -> {
                        Log.e(TAG, "AudioRecord.ERROR")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture loop error", e)
        } finally {
            isCapturing = false
            try {
                record.stop()
            } catch (_: Exception) { }
            record.release()
            audioRecord = null
            Log.d(TAG, "Audio capture stopped and resources released")
        }

        awaitClose {
            isCapturing = false
            try {
                record.stop()
            } catch (_: Exception) { }
            record.release()
            audioRecord = null
            Log.d(TAG, "Audio callbackFlow closed")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Stop the current audio capture session.
     * The [Flow] returned by [startCapture] will complete.
     */
    fun stopCapture() {
        Log.d(TAG, "stopCapture() called")
        isCapturing = false
    }

    /**
     * Calculate the RMS (Root Mean Square) level in decibels for a PCM 16-bit audio chunk.
     *
     * @param audioData PCM 16-bit mono byte array (little-endian)
     * @return RMS level in dB (range roughly -90 to 0 dB).
     *         Returns [SILENCE_THRESHOLD_DB] if the data is empty or silent.
     */
    fun calculateRmsDb(audioData: ByteArray): Float {
        if (audioData.size < 2) return SILENCE_THRESHOLD_DB

        val sampleCount = audioData.size / 2
        var sumSquares = 0.0

        for (i in 0 until sampleCount) {
            val low = audioData[i * 2].toInt() and 0xFF
            val high = audioData[i * 2 + 1].toInt()
            val sample = (high shl 8) or low // Little-endian 16-bit signed
            sumSquares += (sample * sample).toDouble()
        }

        val rms = sqrt(sumSquares / sampleCount)

        // Convert to dB relative to full-scale (32767)
        return if (rms < 1.0) {
            SILENCE_THRESHOLD_DB
        } else {
            (20.0 * log10(rms / Short.MAX_VALUE)).toFloat()
                .coerceAtLeast(SILENCE_THRESHOLD_DB)
        }
    }

    /**
     * Returns true if the current audio chunk is above the silence threshold,
     * indicating speech or significant ambient noise.
     */
    fun isAboveSilenceThreshold(audioData: ByteArray): Boolean {
        return calculateRmsDb(audioData) > SILENCE_THRESHOLD_DB
    }
}
