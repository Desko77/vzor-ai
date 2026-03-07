package com.vzor.ai.speech

import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioRecord
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Acoustic Echo Cancellation (AEC) для режима перевода.
 *
 * Проблема: при двустороннем переводе (Translation Mode C) TTS воспроизводит
 * перевод через динамик, а микрофон ловит этот звук как новый входной сигнал,
 * создавая feedback loop. AEC подавляет эхо от TTS.
 *
 * Реализация:
 * - Android AcousticEchoCanceler (аппаратный, если доступен на устройстве)
 * - Привязывается к AudioRecord сессии
 * - Автоматически включается в режиме перевода
 *
 * Galaxy Z Fold 7 со Snapdragon 8 Elite поддерживает аппаратный AEC.
 */
@Singleton
class AcousticEchoCanceller @Inject constructor() {

    companion object {
        private const val TAG = "AcousticEchoCanceller"
    }

    @Volatile
    private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile
    private var isEnabled = false

    /**
     * Проверяет доступность аппаратного AEC на устройстве.
     */
    fun isAvailable(): Boolean {
        return AcousticEchoCanceler.isAvailable()
    }

    /**
     * Привязывает AEC к аудио-сессии записи.
     *
     * @param audioSessionId ID аудио-сессии из AudioRecord.
     * @return true если AEC успешно создан и включён.
     */
    fun attach(audioSessionId: Int): Boolean {
        if (!isAvailable()) {
            Log.w(TAG, "AcousticEchoCanceler not available on this device")
            return false
        }

        release()

        return try {
            echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also { aec ->
                aec.enabled = true
                isEnabled = true
                Log.d(TAG, "AEC attached to audio session $audioSessionId")
            }
            echoCanceler != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create AEC", e)
            false
        }
    }

    /**
     * Привязывает AEC к AudioRecord.
     */
    fun attachToRecord(audioRecord: AudioRecord): Boolean {
        return attach(audioRecord.audioSessionId)
    }

    /**
     * Включает AEC (если привязан).
     */
    fun enable() {
        echoCanceler?.let {
            it.enabled = true
            isEnabled = true
            Log.d(TAG, "AEC enabled")
        }
    }

    /**
     * Выключает AEC (без освобождения).
     */
    fun disable() {
        echoCanceler?.let {
            it.enabled = false
            isEnabled = false
            Log.d(TAG, "AEC disabled")
        }
    }

    /**
     * Возвращает true если AEC активен.
     */
    fun isActive(): Boolean = isEnabled && echoCanceler != null

    /**
     * Освобождает ресурсы AEC.
     */
    fun release() {
        echoCanceler?.let {
            try {
                it.enabled = false
                it.release()
                Log.d(TAG, "AEC released")
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AEC", e)
            }
        }
        echoCanceler = null
        isEnabled = false
    }
}
