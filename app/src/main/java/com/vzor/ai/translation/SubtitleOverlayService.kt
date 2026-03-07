package com.vzor.ai.translation

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Оверлей субтитров для режима синхронного перевода.
 *
 * Показывает полупрозрачную строку поверх всех приложений
 * с текущим переводом. Используется для сценариев:
 * - Режим A (LISTEN): исходный текст + перевод
 * - Режим B (SPEAK): перевод для собеседника на экране телефона
 * - Режим C (BIDIRECTIONAL): двунаправленный перевод
 *
 * Требует разрешения SYSTEM_ALERT_WINDOW (Settings.canDrawOverlays).
 */
@Singleton
class SubtitleOverlayService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "SubtitleOverlay"
        private const val AUTO_HIDE_DELAY_MS = 5_000L
        private const val TEXT_SIZE_SP = 18f
        private const val OVERLAY_ALPHA = 0.85f
        private const val PADDING_DP = 12
    }

    /**
     * Конфигурация оверлея.
     */
    data class OverlayConfig(
        val position: Position = Position.BOTTOM,
        val textSizeSp: Float = TEXT_SIZE_SP,
        val backgroundColor: Int = 0xCC000000.toInt(),
        val textColor: Int = 0xFFFFFFFF.toInt(),
        val autoHideMs: Long = AUTO_HIDE_DELAY_MS
    )

    enum class Position {
        TOP, BOTTOM, CENTER
    }

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var scope: CoroutineScope? = null
    private var autoHideJob: Job? = null
    private var config = OverlayConfig()

    @Volatile
    private var isShowing = false

    /**
     * Показывает оверлей с субтитрами.
     * Требует SYSTEM_ALERT_WINDOW permission.
     */
    fun show(overlayConfig: OverlayConfig = OverlayConfig()) {
        if (isShowing) return

        if (!canDrawOverlays()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted")
            return
        }

        config = overlayConfig
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val paddingPx = (PADDING_DP * context.resources.displayMetrics.density).toInt()

            val textView = TextView(context).apply {
                textSize = config.textSizeSp
                setTextColor(config.textColor)
                setBackgroundColor(config.backgroundColor)
                alpha = OVERLAY_ALPHA
                setPadding(paddingPx * 2, paddingPx, paddingPx * 2, paddingPx)
                maxLines = 3
                gravity = Gravity.CENTER
                text = ""
            }

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = when (config.position) {
                    Position.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    Position.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    Position.CENTER -> Gravity.CENTER
                }
            }

            windowManager?.addView(textView, layoutParams)
            overlayView = textView
            isShowing = true

            Log.d(TAG, "Subtitle overlay shown (position=${config.position})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show subtitle overlay", e)
        }
    }

    /**
     * Обновляет текст субтитров.
     *
     * @param originalText Исходный текст (может быть null).
     * @param translatedText Переведённый текст.
     * @param showOriginal Показывать ли исходный текст над переводом.
     */
    fun updateText(
        translatedText: String,
        originalText: String? = null,
        showOriginal: Boolean = false
    ) {
        if (!isShowing) return

        val displayText = if (showOriginal && !originalText.isNullOrBlank()) {
            "$originalText\n$translatedText"
        } else {
            translatedText
        }

        scope?.launch(Dispatchers.Main) {
            overlayView?.text = displayText

            // Auto-hide таймер
            autoHideJob?.cancel()
            autoHideJob = launch {
                delay(config.autoHideMs)
                overlayView?.text = ""
            }
        }
    }

    /**
     * Обновляет субтитры из TranslationResult.
     */
    fun updateFromResult(result: TranslationResult, showOriginal: Boolean = true) {
        updateText(
            translatedText = result.translatedText,
            originalText = result.sourceText,
            showOriginal = showOriginal
        )
    }

    /**
     * Привязывает overlay к потоку TranslationResult.
     */
    fun observeTranslations(
        translationFlow: StateFlow<TranslationResult?>,
        showOriginal: Boolean = true
    ): Job? {
        return scope?.launch {
            translationFlow.collect { result ->
                if (result != null) {
                    updateFromResult(result, showOriginal)
                }
            }
        }
    }

    /**
     * Скрывает и уничтожает оверлей.
     */
    fun hide() {
        if (!isShowing) return

        try {
            autoHideJob?.cancel()
            autoHideJob = null
            scope?.launch(Dispatchers.Main) {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                }
                overlayView = null
                windowManager = null
            }
            scope = null
            isShowing = false
            Log.d(TAG, "Subtitle overlay hidden")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide subtitle overlay", e)
        }
    }

    /**
     * Проверяет, отображается ли оверлей.
     */
    fun isVisible(): Boolean = isShowing

    /**
     * Проверяет разрешение на отображение поверх других приложений.
     */
    fun canDrawOverlays(): Boolean {
        return android.provider.Settings.canDrawOverlays(context)
    }
}
