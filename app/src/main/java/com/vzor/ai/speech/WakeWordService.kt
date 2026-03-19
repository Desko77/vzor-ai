package com.vzor.ai.speech

import android.content.Context
import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

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
 * - Использует [WakeWordEngine] для обнаружения wake word.
 * - Если Picovoice Access Key указан в настройках → [PorcupineWakeWordEngine]
 * - Если нет → fallback на [EnergyWakeWordEngine] (energy-based heuristic)
 * - Engine можно переключать в runtime через [switchEngine]
 *
 * Detection pipeline:
 * 1. Получаем PCM 16kHz audio chunks из [AudioStreamHandler]
 * 2. Конвертируем ByteArray → ShortArray
 * 3. Передаём в текущий [WakeWordEngine.process]
 * 4. Если keyword detected → notify [WakeWordListener]
 */
@Singleton
class WakeWordService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "WakeWordService"
    }

    private var listener: WakeWordListener? = null
    @Volatile var isListening: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listeningJob: Job? = null

    /** Текущий движок обнаружения wake word. */
    private var engine: WakeWordEngine? = null

    /** Тип текущего движка (для диагностики). */
    var activeEngineType: String = "none"
        private set

    /**
     * Start listening for the wake word on the given audio stream.
     *
     * Автоматически выбирает лучший доступный движок:
     * - Picovoice Porcupine если Access Key указан в настройках
     * - EnergyWakeWordEngine как fallback
     *
     * @param audioStream Flow of PCM audio chunks from [AudioStreamHandler.startCapture]
     */
    fun startListening(audioStream: Flow<ByteArray>) {
        if (isListening) {
            Log.d(TAG, "Already listening for wake word")
            return
        }

        // Инициализируем движок
        initializeEngine()

        if (engine == null || !engine!!.isReady()) {
            Log.e(TAG, "No wake word engine available")
            return
        }

        isListening = true

        listeningJob = scope.launch {
            Log.d(TAG, "Wake word detection started (engine=$activeEngineType)")

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

    /**
     * Переключает движок обнаружения.
     * Вызывать когда пользователь добавляет/удаляет Picovoice Access Key.
     */
    fun switchEngine(newEngine: WakeWordEngine) {
        engine?.release()
        engine = newEngine
        activeEngineType = newEngine::class.simpleName ?: "unknown"
        Log.d(TAG, "Engine switched to $activeEngineType")
    }

    /**
     * Освобождает ресурсы движка.
     */
    fun releaseEngine() {
        engine?.release()
        engine = null
        activeEngineType = "none"
    }

    // -----------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------

    private fun initializeEngine() {
        if (engine?.isReady() == true) return

        engine?.release()

        // Пробуем Porcupine если есть Access Key
        val accessKey = try {
            runBlocking { preferencesManager.picovoiceAccessKey.first() }
        } catch (e: Exception) {
            ""
        }

        if (accessKey.isNotBlank()) {
            try {
                val porcupineEngine = PorcupineWakeWordEngine(
                    context = context,
                    accessKey = accessKey
                )
                if (porcupineEngine.isReady()) {
                    engine = porcupineEngine
                    activeEngineType = "PorcupineWakeWordEngine"
                    Log.d(TAG, "Using Porcupine wake word engine")
                    return
                } else {
                    porcupineEngine.release()
                    Log.w(TAG, "Porcupine failed to initialize, falling back to energy engine")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Porcupine initialization error: ${e.message}")
            }
        } else {
            Log.d(TAG, "No Picovoice Access Key, using energy-based fallback")
        }

        // Fallback: energy-based engine
        engine = EnergyWakeWordEngine()
        activeEngineType = "EnergyWakeWordEngine"
        Log.d(TAG, "Using energy-based wake word engine (fallback)")
    }

    private fun processAudioChunk(chunk: ByteArray) {
        val engine = this.engine ?: return

        // Конвертируем PCM 16-bit ByteArray в ShortArray
        val shortArray = byteArrayToShortArray(chunk)

        if (engine.process(shortArray)) {
            Log.i(TAG, "Wake word 'Взор' detected! (engine=$activeEngineType)")
            listener?.onWakeWordDetected()
        }
    }

    /**
     * Конвертирует PCM 16-bit little-endian ByteArray в ShortArray.
     */
    internal fun byteArrayToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        return shorts
    }
}
