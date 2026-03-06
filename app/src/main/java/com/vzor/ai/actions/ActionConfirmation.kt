package com.vzor.ai.actions

import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.VzorIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Описание ожидающего подтверждения действия.
 */
data class PendingAction(
    val intent: VzorIntent,
    val description: String,
    val confirmationDeferred: CompletableDeferred<Boolean>
)

/**
 * Управляет подтверждениями для sensitive действий (звонки, сообщения, навигация).
 *
 * UI отображает диалог подтверждения; голосом пользователь говорит «да» / «нет».
 * VoiceOrchestrator переходит в CONFIRMING state и вызывает [confirm] / [deny].
 */
@Singleton
class ActionConfirmation @Inject constructor() {

    companion object {
        /** Действия, требующие подтверждения пользователя. */
        val SENSITIVE_INTENTS = setOf(
            IntentType.CALL_CONTACT,
            IntentType.SEND_MESSAGE,
            IntentType.NAVIGATE
        )
    }

    private val _pendingAction = MutableStateFlow<PendingAction?>(null)
    /** Текущее действие, ожидающее подтверждения (null = нет). */
    val pendingAction: StateFlow<PendingAction?> = _pendingAction.asStateFlow()

    /**
     * Проверяет, требует ли intent подтверждения.
     */
    fun requiresConfirmation(intent: VzorIntent): Boolean {
        return intent.type in SENSITIVE_INTENTS
    }

    /**
     * Запрашивает подтверждение пользователя для действия.
     * Suspends до получения ответа (confirm/deny).
     *
     * @return true если пользователь подтвердил, false если отклонил.
     */
    suspend fun requestConfirmation(intent: VzorIntent): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val description = buildDescription(intent)

        _pendingAction.value = PendingAction(
            intent = intent,
            description = description,
            confirmationDeferred = deferred
        )

        return try {
            deferred.await()
        } finally {
            _pendingAction.value = null
        }
    }

    /**
     * Подтвердить текущее действие.
     */
    fun confirm() {
        _pendingAction.value?.confirmationDeferred?.complete(true)
    }

    /**
     * Отклонить текущее действие.
     */
    fun deny() {
        _pendingAction.value?.confirmationDeferred?.complete(false)
    }

    /**
     * Есть ли ожидающее подтверждение.
     */
    fun hasPendingAction(): Boolean = _pendingAction.value != null

    private fun buildDescription(intent: VzorIntent): String {
        return when (intent.type) {
            IntentType.CALL_CONTACT -> {
                val contact = intent.slots["contact"] ?: intent.slots["name"] ?: "?"
                "Позвонить $contact?"
            }
            IntentType.SEND_MESSAGE -> {
                val contact = intent.slots["contact"] ?: intent.slots["name"] ?: "?"
                val app = intent.slots["app"] ?: "SMS"
                "Отправить сообщение $contact через $app?"
            }
            IntentType.NAVIGATE -> {
                val dest = intent.slots["destination"] ?: intent.slots["address"] ?: "?"
                "Построить маршрут до $dest?"
            }
            else -> "Выполнить действие?"
        }
    }
}
