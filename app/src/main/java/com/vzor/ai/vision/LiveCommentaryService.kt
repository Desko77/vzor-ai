package com.vzor.ai.vision

import android.util.Log
import com.vzor.ai.tts.TtsService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use Case #6: Непрерывный AI-комментарий с камеры.
 *
 * Периодически анализирует кадры с камеры через VisionRouter,
 * сравнивает сцены через EventBuilder и озвучивает изменения через TTS.
 *
 * Оптимизации:
 * - Пропускает кадр, если сцена не изменилась (EventBuilder)
 * - Использует VisionBudgetManager для rate limiting
 * - Снижает частоту при низком заряде батареи (FrameSampler)
 */
@Singleton
class LiveCommentaryService @Inject constructor(
    private val visionRouter: VisionRouter,
    private val eventBuilder: EventBuilder,
    private val frameSampler: FrameSampler,
    private val ttsService: TtsService
) {
    companion object {
        private const val TAG = "LiveCommentary"
        private const val DEFAULT_INTERVAL_MS = 3000L
        private const val MIN_INTERVAL_MS = 1000L
    }

    private val _isActive = MutableStateFlow(false)
    /** Активен ли режим живого комментария. */
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _lastCommentary = MutableStateFlow("")
    /** Последний озвученный комментарий. */
    val lastCommentary: StateFlow<String> = _lastCommentary.asStateFlow()

    private var commentaryScope: CoroutineScope? = null
    private var commentaryJob: Job? = null

    /**
     * Запускает режим живого комментария.
     * Камера должна быть уже активна (через GlassesManager/CameraStreamHandler).
     *
     * @param captureFrame функция для захвата текущего кадра с камеры.
     * @param intervalMs интервал между анализами кадров (мс).
     */
    fun start(
        captureFrame: suspend () -> ByteArray?,
        intervalMs: Long = DEFAULT_INTERVAL_MS
    ) {
        stop()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        commentaryScope = scope
        _isActive.value = true

        frameSampler.setMode(SamplingMode.LOW)
        frameSampler.setAdaptiveMode(true)

        commentaryJob = scope.launch {
            var previousScene = visionRouter.getCachedScene()
            val interval = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)

            while (isActive.value) {
                try {
                    val frame = captureFrame()
                    if (frame == null || frame.isEmpty()) {
                        delay(interval)
                        continue
                    }

                    val result = visionRouter.analyzeScene(
                        imageBytes = frame,
                        prompt = "Опиши что ты видишь кратко. Отвечай на русском.",
                        forceRefresh = true
                    )

                    result.onSuccess { currentScene ->
                        val events = eventBuilder.detectEvents(previousScene, currentScene)

                        if (events.isNotEmpty()) {
                            // Уведомляем FrameSampler для адаптивного повышения FPS
                            events.forEach { _ -> frameSampler.onVisionEvent() }
                            val commentary = buildCommentary(events)
                            _lastCommentary.value = commentary
                            ttsService.speak(commentary)
                            Log.d(TAG, "Commentary: $commentary (${events.size} events)")
                        }

                        previousScene = currentScene
                    }

                    result.onFailure { error ->
                        Log.w(TAG, "Frame analysis failed: ${error.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Commentary loop error", e)
                }

                delay(interval)
            }
        }
    }

    /**
     * Останавливает режим живого комментария.
     */
    fun stop() {
        commentaryJob?.cancel()
        commentaryJob = null
        commentaryScope?.cancel()
        commentaryScope = null
        frameSampler.setMode(SamplingMode.IDLE)
        frameSampler.setAdaptiveMode(false)
        _isActive.value = false
    }

    /**
     * Формирует текст комментария из списка событий.
     */
    private fun buildCommentary(events: List<VisionEvent>): String {
        return events.joinToString(". ") { event ->
            when (event.type) {
                VisionEventType.SCENE_CHANGED -> "Вижу: ${event.description.removePrefix("Scene changed: ")}"
                VisionEventType.NEW_OBJECT -> "Появился ${event.description.removePrefix("New object detected: ")}"
                VisionEventType.OBJECT_REMOVED -> "Исчез ${event.description.removePrefix("Object no longer visible: ")}"
                VisionEventType.FACE_DETECTED -> "Обнаружено лицо"
                VisionEventType.FACE_LOST -> "Лицо потеряно из виду"
                VisionEventType.TEXT_APPEARED -> "Виден текст"
                VisionEventType.TEXT_CHANGED -> "Текст изменился"
                VisionEventType.HAND_GESTURE_DETECTED -> "Жест: ${event.description.removePrefix("Hand gesture detected: ")}"
                VisionEventType.HAND_GESTURE_LOST -> "Жест больше не виден"
            }
        }
    }
}
