package com.vzor.ai.speech

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.SttProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Маршрутизатор STT-сервисов.
 * Делегирует вызовы к WhisperSttService или YandexSttService
 * в зависимости от текущей настройки пользователя.
 */
@Singleton
class SttServiceRouter @Inject constructor(
    private val whisperSttService: WhisperSttService,
    private val yandexSttService: YandexSttService,
    private val prefs: PreferencesManager
) : SttService {

    private val activeService: SttService
        get() {
            val provider = runBlocking { prefs.sttProvider.first() }
            return when (provider) {
                SttProvider.WHISPER -> whisperSttService
                SttProvider.YANDEX -> yandexSttService
                SttProvider.GOOGLE -> whisperSttService // fallback to Whisper
            }
        }

    override fun startListening(): Flow<SttResult> = flow {
        activeService.startListening().collect { emit(it) }
    }

    override fun stopListening() {
        // Останавливаем оба — один из них может быть активен
        whisperSttService.stopListening()
        yandexSttService.stopListening()
    }

    override val isListening: Boolean
        get() = whisperSttService.isListening || yandexSttService.isListening
}
