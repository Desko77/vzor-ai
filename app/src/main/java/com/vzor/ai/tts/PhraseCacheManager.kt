package com.vzor.ai.tts

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages pre-synthesized system phrases for instant TTS playback.
 * Phrases are synthesized at app startup and stored as byte arrays in memory.
 */
@Singleton
class PhraseCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "PhraseCacheManager"
    }

    /**
     * Pre-defined system phrases with Russian and English variants.
     */
    enum class CachedPhrase(val textRu: String, val textEn: String) {
        UNDERSTOOD("Понял", "Understood"),
        EXECUTING("Выполняю", "Executing"),
        CANT_HEAR("Не слышу вас", "I can't hear you"),
        ERROR_RETRY("Ошибка, попробуйте снова", "Error, please try again"),
        LISTENING("Слушаю", "Listening"),
        THINKING("Думаю...", "Thinking...")
    }

    /** Cache: key = "PHRASE_NAME:lang" -> audio bytes */
    private val cache = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    private var isInitialized = false

    /** TTS provider set after DI (avoid circular dependency) */
    private var ttsProvider: TtsProvider? = null

    /**
     * Set the TTS provider to use for pre-synthesis.
     * Must be called before [initialize].
     */
    fun setTtsProvider(provider: TtsProvider) {
        ttsProvider = provider
    }

    /**
     * Pre-synthesize all cached phrases at app startup.
     * Should be called from Application.onCreate or a startup initializer.
     * Synthesizes both Russian and English variants of each phrase.
     */
    suspend fun initialize() = withContext(Dispatchers.IO) {
        val provider = ttsProvider
        if (provider == null) {
            Log.w(TAG, "No TTS provider set, skipping phrase cache initialization")
            return@withContext
        }

        Log.d(TAG, "Initializing phrase cache...")

        try {
            coroutineScope {
                val jobs = CachedPhrase.entries.flatMap { phrase ->
                    listOf(
                        async {
                            try {
                                val audio = provider.synthesize(
                                    text = phrase.textRu,
                                    lang = "ru-RU"
                                )
                                if (audio != null) {
                                    cache["${phrase.name}:ru"] = audio
                                    Log.d(TAG, "Cached RU: ${phrase.name} (${audio.size} bytes)")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to cache RU phrase ${phrase.name}", e)
                            }
                        },
                        async {
                            try {
                                val audio = provider.synthesize(
                                    text = phrase.textEn,
                                    lang = "en-US"
                                )
                                if (audio != null) {
                                    cache["${phrase.name}:en"] = audio
                                    Log.d(TAG, "Cached EN: ${phrase.name} (${audio.size} bytes)")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to cache EN phrase ${phrase.name}", e)
                            }
                        }
                    )
                }
                jobs.awaitAll()
            }

            isInitialized = true
            Log.d(TAG, "Phrase cache initialized: ${cache.size} phrases cached")
        } catch (e: Exception) {
            Log.e(TAG, "Phrase cache initialization failed", e)
        }
    }

    /**
     * Get pre-synthesized audio for a cached phrase.
     *
     * @param phrase The system phrase to retrieve.
     * @param lang Language: "ru" for Russian, "en" for English.
     * @return Audio bytes or null if not cached.
     */
    fun getAudio(phrase: CachedPhrase, lang: String = "ru"): ByteArray? {
        val langKey = when {
            lang.startsWith("ru") -> "ru"
            lang.startsWith("en") -> "en"
            else -> "ru"
        }
        return cache["${phrase.name}:$langKey"]
    }

    /**
     * Get the text for a cached phrase in the specified language.
     */
    fun getText(phrase: CachedPhrase, lang: String = "ru"): String {
        return if (lang.startsWith("ru")) phrase.textRu else phrase.textEn
    }

    /**
     * Check if the cache is initialized and has content.
     */
    fun isCacheReady(): Boolean = isInitialized && cache.isNotEmpty()

    /**
     * Clear all cached audio data.
     */
    fun clear() {
        cache.clear()
        isInitialized = false
    }
}
