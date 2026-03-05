package com.vzor.ai.translation

import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import com.vzor.ai.domain.model.Message
import com.vzor.ai.domain.model.MessageRole
import com.vzor.ai.domain.repository.AiRepository
import com.vzor.ai.speech.SttService
import com.vzor.ai.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates real-time translation between languages using STT, AI translation, and TTS.
 *
 * Three scenarios:
 * - A (LISTEN): Listens to foreign speech -> translates -> shows text + TTS in native language
 * - B (SPEAK): User speaks native -> translates -> TTS in foreign language
 * - C (BIDIRECTIONAL): Auto-detects language -> translates both ways
 */
@Singleton
class TranslationManager @Inject constructor(
    private val sttService: SttService,
    private val ttsManager: TtsManager,
    private val aiRepository: AiRepository,
    private val prefs: PreferencesManager
) {

    companion object {
        private const val TAG = "TranslationManager"

        /** Cyrillic character range for language detection */
        private val CYRILLIC_REGEX = Regex("[\\u0400-\\u04FF]")
        private val LATIN_REGEX = Regex("[a-zA-Z]")
    }

    private val _translationState = MutableStateFlow(TranslationState())
    val translationState: StateFlow<TranslationState> = _translationState.asStateFlow()

    private val _lastTranslation = MutableStateFlow<TranslationResult?>(null)
    val lastTranslation: StateFlow<TranslationResult?> = _lastTranslation.asStateFlow()

    private var currentSession: TranslationSession? = null
    private var translationScope: CoroutineScope? = null
    private var listeningJob: Job? = null

    // Default language pair
    private var sourceLang: String = "ru"
    private var targetLang: String = "en"

    /**
     * Set the source and target languages.
     */
    fun setLanguages(source: String, target: String) {
        sourceLang = source
        targetLang = target
    }

    /**
     * Start translation in the given mode.
     * Creates a new session and begins the STT listening pipeline.
     */
    fun startTranslation(mode: TranslationMode) {
        // Stop any existing session
        stopTranslation()

        val sessionId = UUID.randomUUID().toString()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        translationScope = scope

        currentSession = TranslationSession(
            sessionId = sessionId,
            mode = mode,
            sourceLang = sourceLang,
            targetLang = targetLang
        )

        _translationState.update {
            TranslationState(mode = mode, isActive = true, status = "listening")
        }

        listeningJob = scope.launch {
            try {
                startListeningPipeline(mode)
            } catch (e: Exception) {
                Log.e(TAG, "Translation pipeline error", e)
                _translationState.update {
                    it.copy(status = "error: ${e.message}")
                }
            }
        }
    }

    /**
     * Stop the current translation session.
     */
    fun stopTranslation() {
        listeningJob?.cancel()
        listeningJob = null

        sttService.stopListening()
        ttsManager.cancelAll()

        translationScope?.cancel()
        translationScope = null

        currentSession = null

        _translationState.update {
            TranslationState(mode = null, isActive = false, status = "idle")
        }
    }

    /**
     * Core listening pipeline. Collects STT results, translates, and speaks.
     */
    private suspend fun startListeningPipeline(mode: TranslationMode) {
        sttService.startListening().collect { sttResult ->
            if (!sttResult.isFinal) return@collect
            if (sttResult.text.isBlank()) return@collect

            val text = sttResult.text.trim()
            Log.d(TAG, "STT result: $text (lang: ${sttResult.language})")

            _translationState.update { it.copy(status = "translating") }

            val (srcLang, tgtLang) = resolveLanguagePair(mode, text, sttResult.language)

            val startTime = System.currentTimeMillis()

            try {
                val translated = translateText(text, srcLang, tgtLang)
                val latency = System.currentTimeMillis() - startTime

                val result = TranslationResult(
                    sourceText = text,
                    translatedText = translated,
                    sourceLang = srcLang,
                    targetLang = tgtLang,
                    timestamp = System.currentTimeMillis(),
                    latencyMs = latency
                )

                currentSession?.translations?.add(result)
                _lastTranslation.value = result

                Log.d(TAG, "Translation: '$text' ($srcLang) -> '$translated' ($tgtLang) [${latency}ms]")

                // Speak the translated text
                _translationState.update { it.copy(status = "speaking") }
                ttsManager.speak(translated)

                // Return to listening state
                _translationState.update { it.copy(status = "listening") }
            } catch (e: Exception) {
                Log.e(TAG, "Translation failed for: $text", e)
                _translationState.update { it.copy(status = "error: ${e.message}") }
                // Resume listening after error
                kotlinx.coroutines.delay(500)
                _translationState.update { it.copy(status = "listening") }
            }
        }
    }

    /**
     * Resolve source and target languages based on mode and detected language.
     */
    private fun resolveLanguagePair(
        mode: TranslationMode,
        text: String,
        detectedLang: String
    ): Pair<String, String> {
        return when (mode) {
            TranslationMode.LISTEN -> {
                // Listening to foreign speech -> translate to native
                targetLang to sourceLang
            }
            TranslationMode.SPEAK -> {
                // Speaking native -> translate to foreign
                sourceLang to targetLang
            }
            TranslationMode.BIDIRECTIONAL -> {
                // Auto-detect which language was spoken
                val detected = detectLanguage(text)
                if (detected == sourceLang) {
                    sourceLang to targetLang
                } else {
                    targetLang to sourceLang
                }
            }
        }
    }

    /**
     * Detect the dominant language in a text string.
     */
    private fun detectLanguage(text: String): String {
        val cyrillicCount = CYRILLIC_REGEX.findAll(text).count()
        val latinCount = LATIN_REGEX.findAll(text).count()
        val total = cyrillicCount + latinCount

        if (total == 0) return sourceLang

        val cyrillicRatio = cyrillicCount.toFloat() / total

        return when {
            cyrillicRatio > 0.5f -> "ru"
            else -> "en"
        }
    }

    /**
     * Translate text using the AI repository with a translation prompt.
     */
    private suspend fun translateText(text: String, from: String, to: String): String {
        val langNameFrom = languageDisplayName(from)
        val langNameTo = languageDisplayName(to)

        val messages = listOf(
            Message(
                role = MessageRole.SYSTEM,
                content = "You are a professional translator. Translate accurately and naturally. " +
                        "Return ONLY the translated text, no explanations or extra text."
            ),
            Message(
                role = MessageRole.USER,
                content = "Translate the following from $langNameFrom to $langNameTo: $text"
            )
        )

        val result = aiRepository.sendMessage(messages)
        return result.getOrThrow().trim()
    }

    /**
     * Map language code to display name for the translation prompt.
     */
    private fun languageDisplayName(code: String): String = when (code) {
        "ru" -> "Russian"
        "en" -> "English"
        "de" -> "German"
        "fr" -> "French"
        "es" -> "Spanish"
        "zh" -> "Chinese"
        "ja" -> "Japanese"
        "ko" -> "Korean"
        "ar" -> "Arabic"
        "pt" -> "Portuguese"
        "it" -> "Italian"
        "tr" -> "Turkish"
        else -> code
    }
}
