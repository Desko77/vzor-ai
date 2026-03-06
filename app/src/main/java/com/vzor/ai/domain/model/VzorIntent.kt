package com.vzor.ai.domain.model

data class VzorIntent(
    val type: IntentType,
    val confidence: Float,
    val slots: Map<String, String> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val requiresVision: Boolean = false
)

enum class IntentType {
    GENERAL_QUESTION,   // Обычный вопрос → LLM
    VISION_QUERY,       // "Что ты видишь?" → Vision pipeline
    CALL_CONTACT,       // "Позвони маме" → TelecomManager
    SEND_MESSAGE,       // "Напиши Саше" → WhatsApp/Telegram
    PLAY_MUSIC,         // "Включи музыку" → MediaSession
    NAVIGATE,           // "Навигация домой" → Maps
    SET_REMINDER,       // "Напомни через час" → AlarmManager
    TRANSLATE,          // "Переведи" → Translation mode
    WEB_SEARCH,         // "Найди в интернете" → Tavily
    MEMORY_QUERY,       // "Где я припарковался?" → Persistent Memory
    REPEAT_LAST,        // "Повтори" → SessionLog
    CAPTURE_PHOTO,      // "Сфотографируй" → GlassesManager.capturePhoto (UC#11)
    LIVE_COMMENTARY,    // "Включи комментарий" → LiveCommentaryService (UC#6)
    CONVERSATION_FOCUS, // "Режим фокуса" → ConversationFocusManager (UC#13)
    UNKNOWN
}
