package com.vzor.ai.actions

import android.content.Context
import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.VzorIntent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class ActionResult(
    val success: Boolean,
    val message: String,
    val requiresConfirmation: Boolean = false
)

@Singleton
class ActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val contactPreferenceManager: ContactPreferenceManager
) {
    private val callAction by lazy { CallAction(context) }
    private val messageAction by lazy { MessageAction(context) }
    private val musicAction by lazy { MusicAction(context) }
    private val navigationAction by lazy { NavigationAction(context) }
    private val reminderAction by lazy { ReminderAction(context) }

    suspend fun execute(intent: VzorIntent): ActionResult {
        return when (intent.type) {
            IntentType.CALL_CONTACT -> executeCall(intent)
            IntentType.SEND_MESSAGE -> executeMessage(intent)
            IntentType.PLAY_MUSIC -> executeMusic(intent)
            IntentType.NAVIGATE -> executeNavigation(intent)
            IntentType.SET_REMINDER -> executeReminder(intent)
            IntentType.WEB_SEARCH -> executeWebSearch(intent)
            else -> ActionResult(false, "Действие не поддерживается")
        }
    }

    private suspend fun executeCall(intent: VzorIntent): ActionResult {
        val contactName = intent.slots["contact"] ?: intent.slots["name"]
            ?: return ActionResult(false, "Не указан контакт для звонка")

        // UC#9: используем ContactPreferenceManager для разрешения неоднозначных контактов
        return when (val result = contactPreferenceManager.resolveContact(contactName)) {
            is ContactPreferenceManager.ContactLookupResult.SingleMatch ->
                callAction.call(result.contact.displayName)
            is ContactPreferenceManager.ContactLookupResult.PreferredMatch ->
                callAction.call(result.contact.displayName)
            is ContactPreferenceManager.ContactLookupResult.MultipleMatches ->
                ActionResult(
                    success = false,
                    message = contactPreferenceManager.formatDisambiguationMessage(result),
                    requiresConfirmation = true
                )
            is ContactPreferenceManager.ContactLookupResult.NotFound ->
                ActionResult(false, "Контакт \"$contactName\" не найден в телефонной книге")
        }
    }

    private fun executeMessage(intent: VzorIntent): ActionResult {
        val contact = intent.slots["contact"] ?: intent.slots["name"]
            ?: return ActionResult(false, "Не указан получатель сообщения")
        val text = intent.slots["text"] ?: intent.slots["message"]
            ?: return ActionResult(false, "Не указан текст сообщения")
        val app = intent.slots["app"]?.lowercase()

        return when {
            app == "whatsapp" -> messageAction.sendWhatsApp(contact, text)
            app == "telegram" -> messageAction.sendTelegram(contact, text)
            app == "sms" -> {
                val phone = callAction.lookupContact(contact) ?: contact
                messageAction.sendSms(phone, text)
            }
            else -> {
                // Auto-detect: try WhatsApp first, then Telegram, then SMS
                val whatsAppResult = messageAction.sendWhatsApp(contact, text)
                if (whatsAppResult.success) return whatsAppResult

                val telegramResult = messageAction.sendTelegram(contact, text)
                if (telegramResult.success) return telegramResult

                val phone = callAction.lookupContact(contact) ?: contact
                messageAction.sendSms(phone, text)
            }
        }
    }

    private fun executeMusic(intent: VzorIntent): ActionResult {
        val action = intent.slots["action"]?.lowercase()
        val query = intent.slots["query"] ?: intent.slots["song"] ?: intent.slots["artist"]

        return when (action) {
            "pause", "стоп", "пауза" -> musicAction.pause()
            "next", "следующая", "дальше" -> musicAction.next()
            "previous", "предыдущая", "назад" -> musicAction.previous()
            else -> {
                if (query != null) {
                    musicAction.play(query)
                } else {
                    musicAction.play("")
                }
            }
        }
    }

    private fun executeNavigation(intent: VzorIntent): ActionResult {
        val destination = intent.slots["destination"] ?: intent.slots["address"] ?: intent.slots["place"]
            ?: return ActionResult(false, "Не указан пункт назначения")

        return if (intent.slots.containsKey("address")) {
            navigationAction.navigateToAddress(destination)
        } else {
            navigationAction.navigate(destination)
        }
    }

    private fun executeReminder(intent: VzorIntent): ActionResult {
        val text = intent.slots["text"] ?: intent.slots["reminder"] ?: "Напоминание"
        val delayStr = intent.slots["delay"] ?: intent.slots["minutes"]
        val isTimer = intent.slots["type"]?.lowercase() == "timer"

        val delayMinutes = delayStr?.toIntOrNull() ?: 5

        return if (isTimer) {
            reminderAction.setTimer(delayMinutes)
        } else {
            reminderAction.setReminder(text, delayMinutes)
        }
    }

    private fun executeWebSearch(intent: VzorIntent): ActionResult {
        val query = intent.slots["query"] ?: intent.slots["text"]
            ?: return ActionResult(false, "Не указан поисковый запрос")

        return try {
            val searchIntent = android.content.Intent(
                android.content.Intent.ACTION_WEB_SEARCH
            ).apply {
                putExtra("query", query)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(searchIntent)
            ActionResult(true, "Поиск: $query")
        } catch (e: Exception) {
            try {
                val uri = android.net.Uri.parse("https://www.google.com/search?q=${android.net.Uri.encode(query)}")
                val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                ActionResult(true, "Поиск в браузере: $query")
            } catch (e2: Exception) {
                ActionResult(false, "Не удалось выполнить поиск: ${e2.message}")
            }
        }
    }
}
