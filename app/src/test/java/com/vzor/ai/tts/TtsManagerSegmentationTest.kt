package com.vzor.ai.tts

import android.content.Context
import com.vzor.ai.data.local.PreferencesManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for TtsManager.segmentByLanguage() — mixed RU+EN text segmentation.
 */
class TtsManagerSegmentationTest {

    private lateinit var ttsManager: TtsManager

    @Before
    fun setUp() {
        ttsManager = TtsManager(
            context = mockk<Context>(relaxed = true),
            yandexTts = mockk(relaxed = true),
            googleTts = mockk(relaxed = true),
            phraseCacheManager = mockk(relaxed = true),
            prefs = mockk<PreferencesManager>(relaxed = true)
        )
    }

    @Test
    fun `empty string returns empty list`() {
        assertEquals(emptyList<Pair<String, String>>(), ttsManager.segmentByLanguage(""))
    }

    @Test
    fun `blank string returns empty list`() {
        assertEquals(emptyList<Pair<String, String>>(), ttsManager.segmentByLanguage("   "))
    }

    @Test
    fun `pure Russian text returns single ru segment`() {
        val result = ttsManager.segmentByLanguage("Привет мир как дела")
        assertEquals(1, result.size)
        assertEquals("ru", result[0].second)
        assertEquals("Привет мир как дела", result[0].first)
    }

    @Test
    fun `pure English text returns single en segment`() {
        val result = ttsManager.segmentByLanguage("Hello world how are you")
        assertEquals(1, result.size)
        assertEquals("en", result[0].second)
        assertEquals("Hello world how are you", result[0].first)
    }

    @Test
    fun `mixed RU then EN segments correctly`() {
        val result = ttsManager.segmentByLanguage("Привет это project management")
        assertEquals(2, result.size)
        assertEquals("ru", result[0].second)
        assertTrue(result[0].first.contains("Привет"))
        assertEquals("en", result[1].second)
        assertTrue(result[1].first.contains("project"))
    }

    @Test
    fun `mixed EN then RU segments correctly`() {
        val result = ttsManager.segmentByLanguage("Hello мир")
        assertEquals(2, result.size)
        assertEquals("en", result[0].second)
        assertEquals("ru", result[1].second)
    }

    @Test
    fun `three segments RU-EN-RU`() {
        val result = ttsManager.segmentByLanguage("Открой файл readme потом закрой")
        // "Открой файл" = ru, "readme" = en, "потом закрой" = ru
        assertEquals(3, result.size)
        assertEquals("ru", result[0].second)
        assertEquals("en", result[1].second)
        assertEquals("ru", result[2].second)
    }

    @Test
    fun `numbers attach to preceding segment`() {
        val result = ttsManager.segmentByLanguage("Цена 500 рублей")
        // Numbers are non-alphabetic, should stay with Russian text
        assertEquals(1, result.size)
        assertEquals("ru", result[0].second)
    }

    @Test
    fun `punctuation attaches to preceding segment`() {
        val result = ttsManager.segmentByLanguage("Привет, мир!")
        assertEquals(1, result.size)
        assertEquals("ru", result[0].second)
        assertEquals("Привет, мир!", result[0].first)
    }

    @Test
    fun `only numbers defaults to ru`() {
        val result = ttsManager.segmentByLanguage("12345")
        assertEquals(1, result.size)
        assertEquals("ru", result[0].second)
    }

    // --- Edge cases ---

    @Test
    fun `multiple spaces between languages`() {
        val result = ttsManager.segmentByLanguage("Привет    hello")
        assertEquals(2, result.size)
        assertEquals("ru", result[0].second)
        assertEquals("en", result[1].second)
    }

    @Test
    fun `newline between languages`() {
        val result = ttsManager.segmentByLanguage("Привет\nhello")
        // Newline is non-alphabetic, attaches to current segment; then language switch
        assertEquals(2, result.size)
        assertEquals("ru", result[0].second)
        assertEquals("en", result[1].second)
    }

    @Test
    fun `unicode punctuation between languages`() {
        val result = ttsManager.segmentByLanguage("Привет—hello")
        // Em-dash is non-alphabetic, should segment by language
        assertEquals(2, result.size)
    }

    @Test
    fun `digits at language boundary`() {
        val result = ttsManager.segmentByLanguage("Привет 123 hello world")
        // "Привет 123" should be ru, "hello world" should be en
        assertTrue(result.size >= 2)
        assertEquals("ru", result[0].second)
        assertEquals("en", result.last().second)
    }

    @Test
    fun `only digits and punctuation defaults to ru`() {
        val result = ttsManager.segmentByLanguage("123 !@#")
        assertEquals(1, result.size)
        assertEquals("ru", result[0].second)
    }

    @Test
    fun `long alternating RU-EN-RU-EN-RU`() {
        val result = ttsManager.segmentByLanguage("Русский text Ещё more Конец")
        assertTrue("Expected at least 3 segments", result.size >= 3)
        assertEquals("ru", result[0].second)
        assertEquals("en", result[1].second)
        assertEquals("ru", result.last().second)
    }
}
