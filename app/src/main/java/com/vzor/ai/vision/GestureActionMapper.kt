package com.vzor.ai.vision

import android.util.Log
import com.vzor.ai.actions.ActionResult
import com.vzor.ai.actions.MusicAction
import com.vzor.ai.actions.PhotoCaptureAction
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Маппинг жестов MediaPipe на действия приложения.
 *
 * Поддерживаемые жесты MediaPipe GestureRecognizer:
 * - Thumb_Up → подтверждение / лайк (нет действия, используется как контекст)
 * - Thumb_Down → отмена
 * - Victory → сделать фото (UC#11)
 * - Open_Palm → пауза музыки
 * - Closed_Fist → play/resume музыки
 * - Pointing_Up → следующий трек
 * - ILoveYou → нет действия (декоративный)
 *
 * Используется VisionRouter / LiveCommentaryService для hands-free управления.
 */
@Singleton
class GestureActionMapper @Inject constructor(
    private val photoCaptureAction: PhotoCaptureAction
) {
    companion object {
        private const val TAG = "GestureActionMapper"

        /** Жесты, которые маппятся на действия. */
        val ACTIONABLE_GESTURES = setOf(
            "Victory", "Open_Palm", "Closed_Fist", "Pointing_Up",
            "Thumb_Up", "Thumb_Down"
        )
    }

    /**
     * Результат обработки жеста.
     */
    sealed class GestureResult {
        /** Действие выполнено. */
        data class Executed(val gesture: String, val actionResult: ActionResult) : GestureResult()

        /** Жест распознан, но действие не привязано. */
        data class Acknowledged(val gesture: String, val meaning: String) : GestureResult()

        /** Жест не распознан. */
        data object Unknown : GestureResult()
    }

    /**
     * Обрабатывает обнаруженный жест и выполняет привязанное действие.
     *
     * @param gesture Имя жеста из MediaPipe (e.g. "Victory", "Open_Palm").
     * @return Результат обработки жеста.
     */
    suspend fun handleGesture(gesture: String): GestureResult {
        Log.d(TAG, "Processing gesture: $gesture")

        return when (gesture) {
            "Victory" -> {
                // ✌️ → сделать фото (UC#11 hands-free photo)
                val result = photoCaptureAction.capture()
                GestureResult.Executed(gesture, result)
            }

            "Thumb_Up" -> {
                // 👍 → подтверждение (контекстное)
                GestureResult.Acknowledged(gesture, "Подтверждение")
            }

            "Thumb_Down" -> {
                // 👎 → отмена (контекстное)
                GestureResult.Acknowledged(gesture, "Отмена")
            }

            "Open_Palm" -> {
                // ✋ → стоп/пауза (контекстное, используется LiveCommentary для паузы)
                GestureResult.Acknowledged(gesture, "Стоп / Пауза")
            }

            "Closed_Fist" -> {
                // ✊ → продолжить / play (контекстное)
                GestureResult.Acknowledged(gesture, "Продолжить / Play")
            }

            "Pointing_Up" -> {
                // ☝️ → следующий (контекстное)
                GestureResult.Acknowledged(gesture, "Следующий")
            }

            "ILoveYou" -> {
                GestureResult.Acknowledged(gesture, "🤟")
            }

            else -> {
                Log.d(TAG, "Unknown gesture: $gesture")
                GestureResult.Unknown
            }
        }
    }

    /**
     * Проверяет, является ли жест actionable (имеет привязанное действие).
     */
    fun isActionable(gesture: String): Boolean = gesture in ACTIONABLE_GESTURES
}
