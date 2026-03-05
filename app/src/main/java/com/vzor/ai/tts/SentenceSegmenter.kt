package com.vzor.ai.tts

/**
 * Splits streaming LLM token output into sentence-sized phrases for TTS synthesis.
 *
 * Flush rules:
 * 1. Sentence-ending punctuation (. ? ! ;) with at least 5 words -> flush
 * 2. Buffer age >= 200ms with at least 3 words -> flush (timeout flush)
 * 3. Stream end -> flush any remaining buffer
 */
class SentenceSegmenter(
    private val onSentenceReady: (String) -> Unit
) {
    private val buffer = StringBuilder()
    private var wordCount = 0
    private var bufferStartMs = 0L

    /**
     * Called for each token from the LLM stream.
     */
    fun onToken(token: String) {
        if (buffer.isEmpty()) {
            bufferStartMs = System.currentTimeMillis()
        }
        buffer.append(token)
        wordCount = buffer.toString().trim().split("\\s+".toRegex()).filter { it.isNotEmpty() }.size

        // Flush on sentence end with min 5 words
        if (endsWithSentencePunctuation(token) && wordCount >= 5) {
            flush()
        }
        // Flush on timeout (200ms max buffer) with min 3 words
        else if (System.currentTimeMillis() - bufferStartMs >= 200 && wordCount >= 3) {
            flush()
        }
    }

    /**
     * Called when the LLM stream ends. Flushes any remaining buffer content.
     */
    fun onStreamEnd() {
        if (wordCount > 0) flush()
    }

    /**
     * Reset the segmenter state, clearing all buffers.
     */
    fun reset() {
        buffer.clear()
        wordCount = 0
        bufferStartMs = 0L
    }

    private fun flush() {
        val text = buffer.toString().trim()
        if (text.isNotEmpty()) {
            onSentenceReady(text)
        }
        buffer.clear()
        wordCount = 0
        bufferStartMs = 0L
    }

    private fun endsWithSentencePunctuation(token: String): Boolean {
        val trimmed = token.trimEnd()
        if (trimmed.isEmpty()) return false
        return trimmed.last() in charArrayOf('.', '?', '!', ';')
    }
}
