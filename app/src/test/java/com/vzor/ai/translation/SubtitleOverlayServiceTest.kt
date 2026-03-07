package com.vzor.ai.translation

import org.junit.Assert.*
import org.junit.Test

class SubtitleOverlayServiceTest {

    @Test
    fun `OverlayConfig has correct defaults`() {
        val config = SubtitleOverlayService.OverlayConfig()
        assertEquals(SubtitleOverlayService.Position.BOTTOM, config.position)
        assertEquals(18f, config.textSizeSp, 0.1f)
        assertEquals(5000L, config.autoHideMs)
    }

    @Test
    fun `Position enum has three values`() {
        val positions = SubtitleOverlayService.Position.entries
        assertEquals(3, positions.size)
        assertTrue(positions.contains(SubtitleOverlayService.Position.TOP))
        assertTrue(positions.contains(SubtitleOverlayService.Position.BOTTOM))
        assertTrue(positions.contains(SubtitleOverlayService.Position.CENTER))
    }

    @Test
    fun `OverlayConfig custom values`() {
        val config = SubtitleOverlayService.OverlayConfig(
            position = SubtitleOverlayService.Position.TOP,
            textSizeSp = 24f,
            autoHideMs = 10_000L
        )
        assertEquals(SubtitleOverlayService.Position.TOP, config.position)
        assertEquals(24f, config.textSizeSp, 0.1f)
        assertEquals(10_000L, config.autoHideMs)
    }

    @Test
    fun `TranslationResult for subtitle display`() {
        val result = TranslationResult(
            sourceText = "Привет",
            translatedText = "Hello",
            sourceLang = "ru",
            targetLang = "en",
            latencyMs = 150L
        )
        assertEquals("Привет", result.sourceText)
        assertEquals("Hello", result.translatedText)
        assertEquals(150L, result.latencyMs)
    }
}
