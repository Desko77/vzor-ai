package com.vzor.ai.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

class MessageAction(private val context: Context) {

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val TELEGRAM_PACKAGE = "org.telegram.messenger"
    }

    fun sendWhatsApp(contact: String, text: String): ActionResult {
        if (!isAppInstalled(WHATSAPP_PACKAGE)) {
            return ActionResult(false, "WhatsApp не установлен")
        }

        return try {
            // Try sending via WhatsApp's direct send intent
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(WHATSAPP_PACKAGE)
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra("jid", "") // Will open contact picker if jid is empty
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Try to resolve with contact search via WhatsApp API
            val contactUri = Uri.parse(
                "https://api.whatsapp.com/send?text=${Uri.encode(text)}"
            )
            val whatsappIntent = Intent(Intent.ACTION_VIEW, contactUri).apply {
                setPackage(WHATSAPP_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (resolveIntent(whatsappIntent)) {
                context.startActivity(whatsappIntent)
                ActionResult(true, "Отправка сообщения через WhatsApp для $contact")
            } else if (resolveIntent(sendIntent)) {
                context.startActivity(sendIntent)
                ActionResult(true, "Открыт WhatsApp для отправки сообщения $contact")
            } else {
                ActionResult(false, "Не удалось открыть WhatsApp")
            }
        } catch (e: Exception) {
            ActionResult(false, "Ошибка WhatsApp: ${e.message}")
        }
    }

    fun sendTelegram(contact: String, text: String): ActionResult {
        if (!isAppInstalled(TELEGRAM_PACKAGE)) {
            return ActionResult(false, "Telegram не установлен")
        }

        return try {
            // Open Telegram with share intent
            val telegramUri = Uri.parse("tg://msg?text=${Uri.encode(text)}")
            val telegramIntent = Intent(Intent.ACTION_VIEW, telegramUri).apply {
                setPackage(TELEGRAM_PACKAGE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (resolveIntent(telegramIntent)) {
                context.startActivity(telegramIntent)
                ActionResult(true, "Отправка сообщения через Telegram для $contact")
            } else {
                // Fallback to share intent
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(TELEGRAM_PACKAGE)
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                if (resolveIntent(sendIntent)) {
                    context.startActivity(sendIntent)
                    ActionResult(true, "Открыт Telegram для отправки сообщения $contact")
                } else {
                    ActionResult(false, "Не удалось открыть Telegram")
                }
            }
        } catch (e: Exception) {
            ActionResult(false, "Ошибка Telegram: ${e.message}")
        }
    }

    fun sendSms(phone: String, text: String): ActionResult {
        return try {
            val smsUri = Uri.parse("sms:$phone")
            val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", text)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (resolveIntent(smsIntent)) {
                context.startActivity(smsIntent)
                ActionResult(true, "Отправка SMS на $phone")
            } else {
                ActionResult(false, "Не удалось открыть приложение для SMS")
            }
        } catch (e: Exception) {
            ActionResult(false, "Ошибка отправки SMS: ${e.message}")
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun resolveIntent(intent: Intent): Boolean {
        return context.packageManager.resolveActivity(intent, 0) != null
    }
}
