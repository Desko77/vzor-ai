package com.vzor.ai.domain.model

enum class VoiceState {
    IDLE,           // Ожидание wake word или кнопки
    LISTENING,      // Запись речи пользователя
    PROCESSING,     // STT завершён, классификация intent
    GENERATING,     // LLM генерирует ответ (token stream)
    RESPONDING,     // TTS озвучивает ответ
    CONFIRMING,     // Ожидание подтверждения действия (звонок, сообщение)
    SUSPENDED,      // Системное прерывание (входящий звонок, AudioFocus loss)
    ERROR           // Ошибка, auto-recovery через 3s
}
