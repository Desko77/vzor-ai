package com.vzor.ai.speech

import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.SttProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
    private val offlineSttService: OfflineSttService,
    private val prefs: PreferencesManager
) : SttService {

    private fun resolveService(provider: SttProvider): SttService = when (provider) {
        SttProvider.WHISPER -> whisperSttService
        SttProvider.YANDEX -> yandexSttService
        SttProvider.GOOGLE -> whisperSttService // fallback to Whisper
        SttProvider.OFFLINE -> offlineSttService
    }

    override fun startListening(): Flow<SttResult> = flow {
        val provider = prefs.sttProvider.first()
        resolveService(provider).startListening().collect { emit(it) }
    }

    override fun stopListening() {
        // Останавливаем все — один из них может быть активен
        whisperSttService.stopListening()
        yandexSttService.stopListening()
        offlineSttService.stopListening()
    }

    override val isListening: Boolean
        get() = whisperSttService.isListening || yandexSttService.isListening || offlineSttService.isListening
}
