package com.vzor.ai.vision

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * On-device vision processor using ML Kit Text Recognition.
 * Provides fast, offline OCR for text-focused queries before falling back to cloud VLM.
 */
@Singleton
class OnDeviceVisionProcessor @Inject constructor() {

    companion object {
        private const val TAG = "OnDeviceVisionProcessor"

        /** Minimum confidence for OCR result to be considered reliable. */
        const val MIN_CONFIDENCE = 0.7f

        /** Keywords that indicate a text-focused query. */
        private val TEXT_QUERY_KEYWORDS_RU = listOf(
            "прочитай", "текст", "надпись", "табличк", "вывеск",
            "написан", "буквы", "слова", "что здесь написано",
            "меню", "ценник", "этикетк"
        )
        private val TEXT_QUERY_KEYWORDS_EN = listOf(
            "read", "text", "ocr", "sign", "label", "written", "letters"
        )
    }

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Data class for OCR result.
     */
    data class OcrResult(
        val fullText: String,
        val blocks: List<TextBlock>,
        val confidence: Float
    )

    data class TextBlock(
        val text: String,
        val language: String?,
        val boundingBox: RectF?
    )

    /**
     * Recognizes text in the given image bytes using ML Kit.
     * Returns null if the image cannot be decoded or no text is found.
     */
    suspend fun recognizeText(imageBytes: ByteArray): OcrResult? {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: run {
            Log.w(TAG, "Failed to decode image bytes")
            return null
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { cont ->
            recognizer.process(inputImage)
                .addOnSuccessListener { visionText ->
                    if (visionText.text.isBlank()) {
                        cont.resume(null)
                        return@addOnSuccessListener
                    }

                    val blocks = visionText.textBlocks.map { block ->
                        val rect = block.boundingBox?.let {
                            RectF(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
                        }
                        TextBlock(
                            text = block.text,
                            language = block.recognizedLanguage.takeIf { it.isNotBlank() },
                            boundingBox = rect
                        )
                    }

                    // Estimate confidence based on recognized blocks vs image area
                    val confidence = when {
                        blocks.size > 5 -> 0.9f
                        blocks.size > 2 -> 0.8f
                        blocks.isNotEmpty() -> 0.7f
                        else -> 0f
                    }

                    cont.resume(OcrResult(
                        fullText = visionText.text,
                        blocks = blocks,
                        confidence = confidence
                    ))
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit text recognition failed", e)
                    cont.resume(null)
                }

            cont.invokeOnCancellation {
                // ML Kit tasks are not cancellable, but we can ignore the result
            }
        }
    }

    /**
     * Determines if the given prompt/query is text-focused (OCR scenario).
     * Uses word-boundary matching to avoid false positives on substrings
     * like "текстура" (contains "текст") or "thread" (contains "read").
     */
    fun isTextQuery(prompt: String): Boolean {
        val lower = prompt.lowercase()
        val words = lower.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        return TEXT_QUERY_KEYWORDS_RU.any { kw -> matchesWordBoundary(lower, words, kw) } ||
               TEXT_QUERY_KEYWORDS_EN.any { kw -> matchesWordBoundary(lower, words, kw) }
    }

    /**
     * Word-boundary matching that handles Russian morphology (suffixes up to 3 chars).
     * Multi-word keywords use regex boundaries; single-word keywords use startsWith with length limit.
     */
    private fun matchesWordBoundary(text: String, words: List<String>, keyword: String): Boolean {
        if (keyword.contains(' ')) {
            // Multi-word keyword: match with word boundaries
            val regex = Regex("(?<=\\s|^)${Regex.escape(keyword)}(?=\\s|$|[.,!?;:])")
            return regex.containsMatchIn(text)
        }
        // Single-word: word equals keyword or starts with it (allowing morphological suffixes)
        // Shorter keywords (<=5 chars) get stricter tolerance to avoid false positives
        val maxExtra = if (keyword.length >= 6) 2 else 1
        return words.any { w ->
            // Strip trailing punctuation for comparison
            val clean = w.trimEnd('.', ',', '!', '?', ';', ':', '"', '\'', ')', '(')
            clean == keyword || (clean.startsWith(keyword) && clean.length <= keyword.length + maxExtra)
        }
    }

    /**
     * Release ML Kit resources.
     */
    fun release() {
        recognizer.close()
    }
}
