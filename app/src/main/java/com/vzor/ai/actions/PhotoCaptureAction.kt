package com.vzor.ai.actions

import android.util.Log
import com.vzor.ai.glasses.GlassesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UC#11: Фото/видео hands-free через умные очки.
 *
 * Поддерживаемые команды:
 * - "Сфотографируй" → одиночное фото с камеры очков
 * - "Сделай снимок" → одиночное фото
 *
 * Результат: JPEG bytes сохраняются через GlassesManager,
 * ActionResult содержит статус и размер фото.
 */
@Singleton
class PhotoCaptureAction @Inject constructor(
    private val glassesManager: GlassesManager
) {
    companion object {
        private const val TAG = "PhotoCaptureAction"
    }

    /**
     * Делает фото с камеры очков.
     *
     * @return ActionResult с JPEG данными в imageData, или ошибка.
     */
    suspend fun capture(): ActionResult {
        if (!glassesManager.isCameraAvailable()) {
            Log.w(TAG, "Camera not available — glasses not connected or DAT SDK not ready")
            return ActionResult(
                success = false,
                message = "Камера очков недоступна. Проверьте подключение."
            )
        }

        return try {
            val photoBytes = glassesManager.capturePhoto()
            if (photoBytes != null) {
                Log.d(TAG, "Photo captured: ${photoBytes.size} bytes")
                ActionResult(
                    success = true,
                    message = "Фото сделано (${formatSize(photoBytes.size)})"
                )
            } else {
                ActionResult(
                    success = false,
                    message = "Не удалось сделать фото. Попробуйте ещё раз."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Photo capture failed", e)
            ActionResult(
                success = false,
                message = "Ошибка камеры: ${e.message}"
            )
        }
    }

    private fun formatSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
        }
    }
}
