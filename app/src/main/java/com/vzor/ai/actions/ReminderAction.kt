package com.vzor.ai.actions

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock

class ReminderAction(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "vzor_reminders"
        private const val CHANNEL_NAME = "Vzor Напоминания"
        private const val CHANNEL_DESCRIPTION = "Канал для напоминаний Vzor AI"
        private const val REMINDER_REQUEST_CODE_BASE = 7000
    }

    init {
        ensureNotificationChannel()
    }

    fun setReminder(text: String, delayMinutes: Int): ActionResult {
        return try {
            ensureNotificationChannel()

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerTime = SystemClock.elapsedRealtime() + delayMinutes * 60 * 1000L

            val requestCode = REMINDER_REQUEST_CODE_BASE + (System.currentTimeMillis() % 10000).toInt()

            val reminderIntent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("reminder_text", text)
                putExtra("notification_id", requestCode)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                reminderIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                } else {
                    // Fallback to inexact alarm if exact alarm permission not granted
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                )
            }

            val timeDescription = formatDelay(delayMinutes)
            ActionResult(
                success = true,
                message = "Напоминание установлено: \"$text\" через $timeDescription"
            )
        } catch (e: SecurityException) {
            ActionResult(false, "Нет разрешения на установку точных будильников")
        } catch (e: Exception) {
            ActionResult(false, "Не удалось установить напоминание: ${e.message}")
        }
    }

    fun setTimer(durationMinutes: Int): ActionResult {
        return try {
            val timerIntent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationMinutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Vzor таймер")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (context.packageManager.resolveActivity(timerIntent, 0) != null) {
                context.startActivity(timerIntent)
                val timeDescription = formatDelay(durationMinutes)
                ActionResult(
                    success = true,
                    message = "Таймер установлен на $timeDescription"
                )
            } else {
                // Fallback: use AlarmManager-based reminder as timer
                setReminder("Таймер на ${formatDelay(durationMinutes)}", durationMinutes)
            }
        } catch (e: Exception) {
            ActionResult(false, "Не удалось установить таймер: ${e.message}")
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = CHANNEL_DESCRIPTION
                    enableVibration(true)
                    enableLights(true)
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    private fun formatDelay(minutes: Int): String {
        return when {
            minutes < 1 -> "менее минуты"
            minutes == 1 -> "1 минуту"
            minutes in 2..4 -> "$minutes минуты"
            minutes in 5..20 -> "$minutes минут"
            minutes % 10 == 1 -> "$minutes минуту"
            minutes % 10 in 2..4 -> "$minutes минуты"
            minutes < 60 -> "$minutes минут"
            else -> {
                val hours = minutes / 60
                val remainingMinutes = minutes % 60
                val hourStr = when {
                    hours == 1 -> "1 час"
                    hours in 2..4 -> "$hours часа"
                    else -> "$hours часов"
                }
                if (remainingMinutes > 0) {
                    "$hourStr ${formatDelay(remainingMinutes)}"
                } else {
                    hourStr
                }
            }
        }
    }
}
