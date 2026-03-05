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
     */
    fun isTextQuery(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return TEXT_QUERY_KEYWORDS_RU.any { lower.contains(it) } ||
               TEXT_QUERY_KEYWORDS_EN.any { lower.contains(it) }
    }

    /**
     * Release ML Kit resources.
     */
    fun release() {
        recognizer.close()
    }
}
