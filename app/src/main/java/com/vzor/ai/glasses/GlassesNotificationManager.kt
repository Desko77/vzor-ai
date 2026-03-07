package com.vzor.ai.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.vzor.ai.domain.model.GlassesState
import com.vzor.ai.domain.model.VoiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управляет уведомлениями состояния подключения очков и голосового пайплайна.
 *
 * Persistent notification (foreground service):
 * - Показывает статус подключения очков
 * - Показывает текущее состояние FSM (IDLE, LISTENING, PROCESSING, etc.)
 * - Обновляется при изменениях состояния
 *
 * Используется для Android Foreground Service requirement (Android 14+).
 */
@Singleton
class GlassesNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val glassesManager: GlassesManager
) {
    companion object {
        private const val CHANNEL_ID = "vzor_status"
        private const val CHANNEL_NAME = "Vzor Статус"
        const val NOTIFICATION_ID = 1001

        private const val CHANNEL_EVENTS_ID = "vzor_events"
        private const val CHANNEL_EVENTS_NAME = "Vzor События"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannels()
    }

    /**
     * Создаёт notification channels (Android O+).
     */
    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Канал статуса (low priority, persistent)
            val statusChannel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Статус подключения очков и голосового ассистента"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(statusChannel)

            // Канал событий (high priority)
            val eventsChannel = NotificationChannel(
                CHANNEL_EVENTS_ID,
                CHANNEL_EVENTS_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о событиях (подключение, ошибки)"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(eventsChannel)
        }
    }

    /**
     * Создаёт persistent notification для foreground service.
     */
    fun buildForegroundNotification(
        glassesState: GlassesState = GlassesState.DISCONNECTED,
        voiceState: VoiceState = VoiceState.IDLE
    ): Notification {
        val title = when (glassesState) {
            GlassesState.CONNECTED, GlassesState.STREAMING_AUDIO ->
                "Vzor — Очки подключены"
            GlassesState.CONNECTING ->
                "Vzor — Подключение..."
            GlassesState.CAPTURING_PHOTO ->
                "Vzor — Съёмка фото..."
            GlassesState.ERROR ->
                "Vzor — Ошибка подключения"
            GlassesState.DISCONNECTED ->
                "Vzor — Очки отключены"
        }

        val subtitle = when (voiceState) {
            VoiceState.IDLE -> "Готов к работе"
            VoiceState.LISTENING -> "Слушаю..."
            VoiceState.PROCESSING -> "Обрабатываю..."
            VoiceState.GENERATING -> "Генерирую ответ..."
            VoiceState.RESPONDING -> "Говорю..."
            VoiceState.CONFIRMING -> "Ожидаю подтверждения"
            VoiceState.SUSPENDED -> "Приостановлен"
            VoiceState.ERROR -> "Ошибка"
        }

        val smallIcon = android.R.drawable.ic_dialog_info // TODO: replace with vzor icon

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setSmallIcon(smallIcon)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Обновляет persistent notification.
     */
    fun updateNotification(glassesState: GlassesState, voiceState: VoiceState) {
        val notification = buildForegroundNotification(glassesState, voiceState)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Показывает уведомление о событии (подключение/отключение).
     */
    fun showConnectionEvent(connected: Boolean) {
        val notification = NotificationCompat.Builder(context, CHANNEL_EVENTS_ID)
            .setContentTitle(if (connected) "Очки подключены" else "Очки отключены")
            .setContentText(if (connected) "Ray-Ban Meta готовы к работе" else "Проверьте Bluetooth")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    /**
     * Отменяет persistent notification.
     */
    fun cancel() {
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
