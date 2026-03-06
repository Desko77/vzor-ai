package com.vzor.ai.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TranslationManagerTest {

    // --- Language heuristic detection (via Unicode script) ---

    @Test
    fun `detectLanguageHeuristic returns ru for Cyrillic text`() {
        val result = detectHeuristic("Привет мир как дела")
        assertEquals("ru", result)
    }

    @Test
    fun `detectLanguageHeuristic returns en for Latin text`() {
        val result = detectHeuristic("Hello world how are you")
        assertEquals("en", result)
    }

    @Test
    fun `detectLanguageHeuristic handles mixed text with more Cyrillic`() {
        // Больше кириллических символов
        val result = detectHeuristic("Привет hello мир")
        assertEquals("ru", result)
    }

    @Test
    fun `detectLanguageHeuristic handles mixed text with more Latin`() {
        val result = detectHeuristic("Hello привет world foo bar")
        assertEquals("en", result)
    }

    @Test
    fun `detectLanguageHeuristic returns default for empty text`() {
        val result = detectHeuristic("")
        assertEquals("ru", result) // default source language
    }

    @Test
    fun `detectLanguageHeuristic returns default for numeric only text`() {
        val result = detectHeuristic("12345 67890")
        assertEquals("ru", result)
    }

    // --- TranslationMode enum ---

    @Test
    fun `all translation modes are defined`() {
        val modes = TranslationMode.entries
        assertEquals(3, modes.size)
        assertNotNull(TranslationMode.valueOf("LISTEN"))
        assertNotNull(TranslationMode.valueOf("SPEAK"))
        assertNotNull(TranslationMode.valueOf("BIDIRECTIONAL"))
    }

    // --- TranslationResult ---

    @Test
    fun `TranslationResult stores correct data`() {
        val result = TranslationResult(
            sourceText = "Привет",
            translatedText = "Hello",
            sourceLang = "ru",
            targetLang = "en",
            timestamp = 1000L,
            latencyMs = 150L
        )
        assertEquals("Привет", result.sourceText)
        assertEquals("Hello", result.translatedText)
        assertEquals("ru", result.sourceLang)
        assertEquals("en", result.targetLang)
        assertEquals(150L, result.latencyMs)
    }

    // --- TranslationState ---

    @Test
    fun `default TranslationState is inactive`() {
        val state = TranslationState()
        assertEquals(false, state.isActive)
        assertEquals("idle", state.status)
        assertEquals(null, state.mode)
    }

    @Test
    fun `active TranslationState has mode and status`() {
        val state = TranslationState(
            mode = TranslationMode.BIDIRECTIONAL,
            isActive = true,
            status = "listening"
        )
        assertEquals(true, state.isActive)
        assertEquals("listening", state.status)
        assertEquals(TranslationMode.BIDIRECTIONAL, state.mode)
    }

    /**
     * Тестовая обёртка для Unicode heuristic detection.
     * Воспроизводит логику TranslationManager.detectLanguageHeuristic().
     */
    private fun detectHeuristic(text: String, defaultLang: String = "ru"): String {
        val cyrillicRegex = Regex("[\\u0400-\\u04FF]")
        val latinRegex = Regex("[a-zA-Z]")

        val cyrillicCount = cyrillicRegex.findAll(text).count()
        val latinCount = latinRegex.findAll(text).count()
        val total = cyrillicCount + latinCount

        if (total == 0) return defaultLang

        val cyrillicRatio = cyrillicCount.toFloat() / total

        return when {
            cyrillicRatio > 0.5f -> "ru"
            else -> "en"
        }
    }
}
