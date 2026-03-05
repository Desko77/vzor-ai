package com.vzor.ai.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for OnDeviceVisionProcessor.isTextQuery() — text query detection logic.
 * ML Kit OCR itself requires Android instrumentation and cannot be unit tested here.
 */
class OnDeviceVisionProcessorTest {

    private lateinit var processor: OnDeviceVisionProcessor

    @Before
    fun setUp() {
        processor = OnDeviceVisionProcessor()
    }

    // --- isTextQuery: Russian keywords ---

    @Test
    fun `isTextQuery detects прочитай`() {
        assertTrue(processor.isTextQuery("Прочитай эту табличку"))
    }

    @Test
    fun `isTextQuery detects текст`() {
        assertTrue(processor.isTextQuery("Какой текст на этом знаке?"))
    }

    @Test
    fun `isTextQuery detects надпись`() {
        assertTrue(processor.isTextQuery("Что за надпись?"))
    }

    @Test
    fun `isTextQuery detects вывеск`() {
        assertTrue(processor.isTextQuery("Прочитай вывеску"))
    }

    @Test
    fun `isTextQuery detects меню`() {
        assertTrue(processor.isTextQuery("Покажи меню"))
    }

    @Test
    fun `isTextQuery detects ценник`() {
        assertTrue(processor.isTextQuery("Сколько стоит? Прочитай ценник"))
    }

    // --- isTextQuery: English keywords ---

    @Test
    fun `isTextQuery detects read`() {
        assertTrue(processor.isTextQuery("Read this sign"))
    }

    @Test
    fun `isTextQuery detects text`() {
        assertTrue(processor.isTextQuery("What text is on the screen?"))
    }

    @Test
    fun `isTextQuery detects OCR`() {
        assertTrue(processor.isTextQuery("Run OCR on this image"))
    }

    // --- isTextQuery: non-text queries ---

    @Test
    fun `isTextQuery returns false for general vision query`() {
        assertFalse(processor.isTextQuery("Что ты видишь?"))
    }

    @Test
    fun `isTextQuery returns false for object identification`() {
        assertFalse(processor.isTextQuery("Определи что это за предмет"))
    }

    @Test
    fun `isTextQuery returns false for empty string`() {
        assertFalse(processor.isTextQuery(""))
    }

    @Test
    fun `isTextQuery returns false for generic photo analysis`() {
        assertFalse(processor.isTextQuery("Опиши подробно что ты видишь на этом изображении"))
    }

    @Test
    fun `isTextQuery is case insensitive`() {
        assertTrue(processor.isTextQuery("ПРОЧИТАЙ ЭТО"))
        assertTrue(processor.isTextQuery("READ THIS"))
    }
}
