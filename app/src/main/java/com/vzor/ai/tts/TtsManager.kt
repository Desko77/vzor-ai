package com.vzor.ai.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming TTS pipeline manager.
 *
 * Pipeline: LLM token stream -> TokenBuffer -> SentenceSegmenter -> TTS synthesis -> AudioQueue -> BT playback
 *
 * Supports:
 * - Streaming TTS from LLM token output (sentence-by-sentence)
 * - Pre-cached phrase playback (instant)
 * - Full text synthesis (non-streaming)
 * - Language detection for mixed RU/EN text
 * - Yandex SpeechKit for Russian, Google TTS for English
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val yandexTts: YandexTtsProvider,
    private val googleTts: GoogleTtsProvider,
    private val phraseCacheManager: PhraseCacheManager,
    private val prefs: PreferencesManager
) : TtsService {

    companion object {
        private const val TAG = "TtsManager"

        /** Cyrillic character range for language detection */
        private val CYRILLIC_REGEX = Regex("[\\u0400-\\u04FF]")
        private val LATIN_REGEX = Regex("[a-zA-Z]")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playbackJob: Job? = null

    /** Audio queue for sequential playback of synthesized chunks */
    private val audioQueue = ConcurrentLinkedQueue<AudioChunk>()
    private val isPlaying = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    private var audioTrack: AudioTrack? = null

    /** Internal token buffer for accumulating LLM tokens */
    private val tokenBuffer = StringBuilder()
    private var wordCount = 0
    private var bufferStartTime = 0L

    /**
     * Data class for queued audio chunks.
     */
    private data class AudioChunk(
        val audio: ByteArray,
        val sampleRate: Int = YandexTtsProvider.SAMPLE_RATE
    )

    init {
        // Wire up the sentence segmenter to synthesize and queue audio
        // Note: SentenceSegmenter is created fresh for each streaming session
    }

    /**
     * Feed a token from the LLM stream into the TTS pipeline.
     * Tokens accumulate until the SentenceSegmenter determines a sentence is ready,
     * then that sentence is synthesized and queued for playback.
     */
    override fun onToken(token: String) {
        if (isCancelled.get()) return

        if (tokenBuffer.isEmpty()) {
            bufferStartTime = System.currentTimeMillis()
        }
        tokenBuffer.append(token)
        wordCount = tokenBuffer.toString().trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

        // Check flush conditions
        val shouldFlush = when {
            // Sentence-ending punctuation with min 5 words
            endsWithSentencePunctuation(token) && wordCount >= 5 -> true
            // Timeout flush: 200ms with min 3 words
            System.currentTimeMillis() - bufferStartTime >= 200 && wordCount >= 3 -> true
            else -> false
        }

        if (shouldFlush) {
            val text = tokenBuffer.toString().trim()
            tokenBuffer.clear()
            wordCount = 0
            bufferStartTime = 0L

            if (text.isNotEmpty()) {
                synthesizeAndQueue(text)
            }
        }
    }

    /**
     * Called when the LLM stream ends. Flushes any remaining buffered tokens.
     */
    override fun onStreamEnd() {
        val remainingText = tokenBuffer.toString().trim()
        tokenBuffer.clear()
        wordCount = 0
        bufferStartTime = 0L

        if (remainingText.isNotEmpty()) {
            synthesizeAndQueue(remainingText)
        }
    }

    /**
     * Speak a pre-cached phrase immediately, bypassing the synthesis pipeline.
     */
    fun speakCachedPhrase(phrase: PhraseCacheManager.CachedPhrase) {
        val lang = "ru" // Default to Russian
        val audio = phraseCacheManager.getAudio(phrase, lang)
        if (audio != null) {
            audioQueue.add(AudioChunk(audio, YandexTtsProvider.SAMPLE_RATE))
            ensurePlayback()
        } else {
            // Fallback: synthesize the phrase text on the fly
            val text = phraseCacheManager.getText(phrase, lang)
            synthesizeAndQueue(text)
        }
    }

    /**
     * Stop all: clear buffers, stop TTS synthesis, stop audio playback.
     * Реализация [TtsService.stop].
     */
    override fun stop() {
        cancelAll()
    }

    /**
     * Cancel all: clear buffers, stop TTS synthesis, stop audio playback.
     */
    fun cancelAll() {
        isCancelled.set(true)

        // Clear token buffer
        tokenBuffer.clear()
        wordCount = 0
        bufferStartTime = 0L

        // Clear audio queue
        audioQueue.clear()

        // Stop current playback
        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio track", e)
        }

        // Cancel all coroutines
        scope.coroutineContext.cancelChildren()

        // Stop TTS providers
        yandexTts.stop()
        googleTts.stop()

        isPlaying.set(false)
        isCancelled.set(false) // Reset for next use
    }

    /**
     * Speak full text (non-streaming). Synthesizes and plays the entire text.
     */
    override suspend fun speak(text: String) {
        if (text.isBlank()) return

        isCancelled.set(false)
        val lang = detectLanguage(text)

        when {
            lang == "ru" -> {
                val audio = yandexTts.synthesize(text, lang = "ru-RU")
                if (audio != null) {
                    playAudioBytes(audio, YandexTtsProvider.SAMPLE_RATE)
                } else {
                    // Fallback to Google TTS
                    googleTts.speakDirect(text, "ru-RU")
                }
            }
            lang == "en" -> {
                googleTts.speakDirect(text, "en-US")
            }
            else -> {
                // Mixed RU+EN — segment by language and synthesize each part
                val segments = segmentByLanguage(text)
                for ((segment, segLang) in segments) {
                    if (isCancelled.get()) break
                    when (segLang) {
                        "ru" -> {
                            val audio = yandexTts.synthesize(segment, lang = "ru-RU")
                            if (audio != null) {
                                playAudioBytes(audio, YandexTtsProvider.SAMPLE_RATE)
                            } else {
                                googleTts.speakDirect(segment, "ru-RU")
                            }
                        }
                        "en" -> googleTts.speakDirect(segment, "en-US")
                    }
                }
            }
        }
    }

    /**
     * Segment mixed-language text into contiguous runs of the same script.
     * Each segment is a Pair of (text, languageCode) where languageCode is "ru" or "en".
     *
     * Algorithm:
     * 1. Iterate characters, track current script (CYRILLIC/LATIN)
     * 2. Non-alphabetic chars (digits, punctuation, spaces) attach to current segment
     * 3. When script changes at a word boundary, start a new segment
     */
    fun segmentByLanguage(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()

        val segments = mutableListOf<Pair<StringBuilder, String>>()
        var currentLang = "ru" // Default to Russian
        var currentSegment = StringBuilder()
        var foundFirstAlpha = false

        for (char in text) {
            val charLang = when {
                char.isCyrillic() -> "ru"
                char.isLatinLetter() -> "en"
                else -> null // non-alphabetic: attach to current segment
            }

            if (charLang != null && !foundFirstAlpha) {
                currentLang = charLang
                foundFirstAlpha = true
            }

            if (charLang != null && charLang != currentLang) {
                // Language switch — find word boundary (split at last space)
                val built = currentSegment.toString()
                val lastSpace = built.lastIndexOf(' ')
                if (lastSpace > 0) {
                    // Split: everything up to last space stays in current segment
                    val keep = built.substring(0, lastSpace + 1)
                    val move = built.substring(lastSpace + 1)
                    if (keep.isNotBlank()) {
                        segments.add(StringBuilder(keep) to currentLang)
                    }
                    currentSegment = StringBuilder(move)
                } else {
                    if (currentSegment.isNotBlank()) {
                        segments.add(currentSegment to currentLang)
                    }
                    currentSegment = StringBuilder()
                }
                currentLang = charLang
            }

            currentSegment.append(char)
        }

        if (currentSegment.isNotBlank()) {
            segments.add(currentSegment to currentLang)
        }

        return segments.map { (sb, lang) -> sb.toString().trim() to lang }
            .filter { it.first.isNotEmpty() }
    }

    private fun Char.isCyrillic(): Boolean =
        this in '\u0400'..'\u04FF'

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    /**
     * Detect the dominant language in a text string.
     * Supports Russian (Cyrillic) and English (Latin) detection.
     *
     * @return "ru" for Russian, "en" for English, "mixed" for mixed content.
     */
    private fun detectLanguage(text: String): String {
        val cyrillicCount = CYRILLIC_REGEX.findAll(text).count()
        val latinCount = LATIN_REGEX.findAll(text).count()
        val total = cyrillicCount + latinCount

        if (total == 0) return "ru" // Default to Russian for non-alphabetic text

        val cyrillicRatio = cyrillicCount.toFloat() / total

        return when {
            cyrillicRatio > 0.7f -> "ru"
            cyrillicRatio < 0.3f -> "en"
            else -> "mixed"
        }
    }

    /**
     * Synthesize text and add the resulting audio to the playback queue.
     */
    private fun synthesizeAndQueue(text: String) {
        scope.launch {
            try {
                val lang = detectLanguage(text)
                when (lang) {
                    "en" -> {
                        googleTts.speakDirect(text, "en-US")
                    }
                    "mixed" -> {
                        // Segment mixed text and synthesize each part with appropriate provider
                        for ((segment, segLang) in segmentByLanguage(text)) {
                            if (isCancelled.get()) break
                            when (segLang) {
                                "ru" -> {
                                    val audio = yandexTts.synthesize(segment, lang = "ru-RU")
                                    if (audio != null) {
                                        audioQueue.add(AudioChunk(audio, YandexTtsProvider.SAMPLE_RATE))
                                        ensurePlayback()
                                    } else {
                                        googleTts.speakDirect(segment, "ru-RU")
                                    }
                                }
                                "en" -> googleTts.speakDirect(segment, "en-US")
                            }
                        }
                    }
                    else -> {
                        val audio = yandexTts.synthesize(text, lang = "ru-RU")
                        if (audio != null) {
                            audioQueue.add(AudioChunk(audio, YandexTtsProvider.SAMPLE_RATE))
                            ensurePlayback()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "TTS synthesis failed for: ${text.take(50)}...", e)
            }
        }
    }

    /**
     * Ensure the audio playback loop is running.
     */
    private fun ensurePlayback() {
        if (isPlaying.compareAndSet(false, true)) {
            playbackJob = scope.launch(Dispatchers.IO) {
                try {
                    while (audioQueue.isNotEmpty() && !isCancelled.get()) {
                        val chunk = audioQueue.poll() ?: break
                        playAudioBytes(chunk.audio, chunk.sampleRate)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Playback error", e)
                } finally {
                    isPlaying.set(false)
                    // Check if more audio was added while we were finishing
                    if (audioQueue.isNotEmpty() && !isCancelled.get()) {
                        ensurePlayback()
                    }
                }
            }
        }
    }

    /**
     * Play raw PCM audio bytes through AudioTrack.
     */
    private suspend fun playAudioBytes(
        audioBytes: ByteArray,
        sampleRate: Int
    ) = withContext(Dispatchers.IO) {
        if (isCancelled.get() || audioBytes.isEmpty()) return@withContext

        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize.coerceAtLeast(audioBytes.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        audioTrack = track

        try {
            track.write(audioBytes, 0, audioBytes.size)
            track.play()

            // Wait for playback to complete
            val durationMs = (audioBytes.size.toLong() * 1000) / (sampleRate * 2) // 16-bit = 2 bytes/sample
            kotlinx.coroutines.delay(durationMs + 50) // Small buffer for safety
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack playback error", e)
        } finally {
            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioTrack", e)
            }
            if (audioTrack == track) {
                audioTrack = null
            }
        }
    }

    private fun endsWithSentencePunctuation(token: String): Boolean {
        val trimmed = token.trimEnd()
        if (trimmed.isEmpty()) return false
        return trimmed.last() in charArrayOf('.', '?', '!', ';')
    }
}
