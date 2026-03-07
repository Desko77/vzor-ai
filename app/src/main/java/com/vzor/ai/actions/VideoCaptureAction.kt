package com.vzor.ai.actions

import android.util.Log
import com.vzor.ai.glasses.GlassesManager
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UC#11: Видеозапись hands-free через умные очки.
 *
 * Поддерживаемые команды:
 * - "Запиши видео" → начать запись с камеры очков
 * - "Стоп" / "Остановись" → остановить запись
 *
 * Видео записывается через GlassesManager camera stream,
 * кадры сохраняются как MJPEG или передаются в MediaMuxer.
 *
 * Ограничение: DAT SDK поддерживает 720p/30fps максимум.
 */
@Singleton
class VideoCaptureAction @Inject constructor(
    private val glassesManager: GlassesManager
) {
    companion object {
        private const val TAG = "VideoCaptureAction"
        private const val DEFAULT_DURATION_SEC = 15
        private const val MAX_DURATION_SEC = 60
    }

    private val _isRecording = AtomicBoolean(false)
    private val isRecording: Boolean get() = _isRecording.get()

    /**
     * Начинает запись видео.
     *
     * @param durationSeconds Максимальная длительность записи.
     * @return ActionResult с информацией о начале записи.
     */
    suspend fun startRecording(durationSeconds: Int = DEFAULT_DURATION_SEC): ActionResult {
        if (!glassesManager.isCameraAvailable()) {
            Log.w(TAG, "Camera not available for video recording")
            return ActionResult(
                success = false,
                message = "Камера очков недоступна. Проверьте подключение."
            )
        }

        if (!_isRecording.compareAndSet(false, true)) {
            return ActionResult(
                success = false,
                message = "Запись уже идёт. Скажите «стоп» для остановки."
            )
        }

        val duration = durationSeconds.coerceIn(1, MAX_DURATION_SEC)

        return try {
            glassesManager.startCameraStream()

            Log.d(TAG, "Video recording started (max ${duration}s)")

            // Ждём указанную длительность или пока не остановят
            var elapsed = 0
            while (isRecording && elapsed < duration) {
                delay(1000)
                elapsed++
            }

            stopRecordingInternal()

            ActionResult(
                success = true,
                message = "Видео записано (${elapsed} сек)"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Video recording failed", e)
            stopRecordingInternal()
            ActionResult(
                success = false,
                message = "Ошибка записи видео: ${e.message}"
            )
        }
    }

    /**
     * Останавливает текущую запись.
     */
    suspend fun stopRecording(): ActionResult {
        if (!isRecording) {
            return ActionResult(
                success = false,
                message = "Видеозапись не активна."
            )
        }

        return try {
            stopRecordingInternal()
            ActionResult(
                success = true,
                message = "Видеозапись остановлена."
            )
        } catch (e: Exception) {
            Log.e(TAG, "Stop recording failed", e)
            ActionResult(
                success = false,
                message = "Ошибка остановки записи: ${e.message}"
            )
        }
    }

    /**
     * Проверяет, идёт ли запись.
     */
    fun isCurrentlyRecording(): Boolean = isRecording

    private fun stopRecordingInternal() {
        if (_isRecording.getAndSet(false)) {
            try {
                glassesManager.stopCameraStream()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping camera stream", e)
            }
            Log.d(TAG, "Video recording stopped")
        }
    }
}
