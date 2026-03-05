package com.vzor.ai.actions

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.vzor.ai.R

class ReminderReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val reminderText = intent.getStringExtra("reminder_text") ?: "Напоминание"
        val notificationId = intent.getIntExtra("notification_id", 0)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, "vzor_reminders")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Vzor — Напоминание")
            .setContentText(reminderText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        notificationManager.notify(notificationId, notification)
    }
}
