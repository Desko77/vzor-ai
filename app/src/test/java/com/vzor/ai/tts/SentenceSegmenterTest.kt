package com.vzor.ai.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SentenceSegmenterTest {

    private val sentences = mutableListOf<String>()
    private lateinit var segmenter: SentenceSegmenter

    @Before
    fun setUp() {
        sentences.clear()
        segmenter = SentenceSegmenter { sentences.add(it) }
    }

    // --- Sentence end punctuation ---

    @Test
    fun `flushes on period with 5+ words`() {
        "Это простое тестовое предложение вот.".split(" ").forEach { segmenter.onToken("$it ") }
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].contains("предложение"))
    }

    @Test
    fun `flushes on question mark`() {
        "Как дела у тебя сегодня?".split(" ").forEach { segmenter.onToken("$it ") }
        assertEquals(1, sentences.size)
    }

    @Test
    fun `flushes on exclamation mark`() {
        "Это просто замечательный прекрасный день!".split(" ").forEach { segmenter.onToken("$it ") }
        assertEquals(1, sentences.size)
    }

    // --- Stream end ---

    @Test
    fun `flushes remaining on stream end`() {
        segmenter.onToken("Привет ")
        segmenter.onToken("мир")
        segmenter.onStreamEnd()
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].contains("Привет"))
    }

    @Test
    fun `empty buffer on stream end produces no sentence`() {
        segmenter.onStreamEnd()
        assertTrue(sentences.isEmpty())
    }

    // --- Short sentences don't flush on punctuation ---

    @Test
    fun `does not flush on period with less than 5 words`() {
        segmenter.onToken("Да.")
        // Should not flush yet (only 1 word)
        assertEquals(0, sentences.size)
        // But stream end should flush it
        segmenter.onStreamEnd()
        assertEquals(1, sentences.size)
    }

    // --- Reset ---

    @Test
    fun `reset clears buffer`() {
        segmenter.onToken("Привет ")
        segmenter.reset()
        segmenter.onStreamEnd()
        assertTrue(sentences.isEmpty())
    }

    // --- Mixed RU and EN ---

    @Test
    fun `handles mixed language tokens`() {
        "Открой Spotify и поставь Imagine Dragons пожалуйста.".split(" ").forEach {
            segmenter.onToken("$it ")
        }
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].contains("Spotify"))
        assertTrue(sentences[0].contains("Imagine"))
    }

    // --- Multiple sentences ---

    @Test
    fun `multiple sentences produce multiple flushes`() {
        val text1 = "Первое предложение тут вот закончилось. "
        val text2 = "Второе предложение тут тоже вот закончилось."
        text1.split(" ").filter { it.isNotEmpty() }.forEach { segmenter.onToken("$it ") }
        text2.split(" ").filter { it.isNotEmpty() }.forEach { segmenter.onToken("$it ") }
        segmenter.onStreamEnd()
        assertTrue(sentences.size >= 2)
    }

    // --- EN-only text ---

    @Test
    fun `flushes on period with EN-only text`() {
        "Hello world how are you today.".split(" ").forEach { segmenter.onToken("$it ") }
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].contains("Hello"))
        assertTrue(sentences[0].contains("today"))
    }

    @Test
    fun `EN-only question mark flushes sentence`() {
        "What is the capital of France today?".split(" ").forEach { segmenter.onToken("$it ") }
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].contains("France"))
    }

    // --- RU↔EN alternation ---

    @Test
    fun `alternating RU and EN sentences flush separately`() {
        val text = "Привет мир как дела сегодня. Hello world how are you today. Снова русский текст вот тут."
        text.split(" ").filter { it.isNotEmpty() }.forEach { segmenter.onToken("$it ") }
        segmenter.onStreamEnd()
        assertTrue("Expected at least 3 sentences, got ${sentences.size}", sentences.size >= 3)
    }

    @Test
    fun `mixed RU and EN in single sentence stays together`() {
        "Запусти YouTube и найди Imagine Dragons клип пожалуйста.".split(" ").forEach {
            segmenter.onToken("$it ")
        }
        assertEquals(1, sentences.size)
        assertTrue(sentences[0].contains("YouTube"))
        assertTrue(sentences[0].contains("Imagine"))
    }

    // --- Semicolon punctuation ---

    @Test
    fun `flushes on semicolon with 5+ words`() {
        "Первый пункт вот такой длинный;".split(" ").forEach { segmenter.onToken("$it ") }
        assertEquals(1, sentences.size)
    }
}
