package com.vzor.ai.orchestrator

import com.vzor.ai.domain.model.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IntentClassifierTest {

    private lateinit var classifier: IntentClassifier

    @Before
    fun setUp() {
        classifier = IntentClassifier()
    }

    // --- Vision ---

    @Test
    fun `classify vision query - что видишь`() {
        val result = classifier.classify("Что видишь?")
        assertEquals(IntentType.VISION_QUERY, result.type)
        assertTrue(result.requiresVision)
        assertTrue(result.confidence >= 0.8f)
    }

    @Test
    fun `classify vision query - опиши что`() {
        val result = classifier.classify("Опиши что перед тобой")
        assertEquals(IntentType.VISION_QUERY, result.type)
    }

    @Test
    fun `classify vision query - прочитай`() {
        val result = classifier.classify("Прочитай эту табличку")
        assertEquals(IntentType.VISION_QUERY, result.type)
    }

    // --- Call ---

    @Test
    fun `classify call contact - позвони маме`() {
        val result = classifier.classify("Позвони маме")
        assertEquals(IntentType.CALL_CONTACT, result.type)
        assertTrue(result.requiresConfirmation)
        assertEquals("маме", result.slots["contact"])
    }

    @Test
    fun `classify call contact - набери Сашу`() {
        val result = classifier.classify("Набери Сашу")
        assertEquals(IntentType.CALL_CONTACT, result.type)
        assertEquals("сашу", result.slots["contact"])
    }

    // --- Message ---

    @Test
    fun `classify send message - напиши`() {
        val result = classifier.classify("Напиши Ивану привет")
        assertEquals(IntentType.SEND_MESSAGE, result.type)
        assertTrue(result.requiresConfirmation)
        assertNotNull(result.slots["contact"])
    }

    // --- Music ---

    @Test
    fun `classify play music`() {
        val result = classifier.classify("Включи музыку")
        assertEquals(IntentType.PLAY_MUSIC, result.type)
    }

    @Test
    fun `classify play music - пауза`() {
        val result = classifier.classify("Пауза")
        assertEquals(IntentType.PLAY_MUSIC, result.type)
    }

    // --- Navigation ---

    @Test
    fun `classify navigate - маршрут до дома`() {
        val result = classifier.classify("Маршрут до дома")
        assertEquals(IntentType.NAVIGATE, result.type)
        assertNotNull(result.slots["destination"])
    }

    @Test
    fun `classify navigate - как доехать`() {
        val result = classifier.classify("Как доехать до работы")
        assertEquals(IntentType.NAVIGATE, result.type)
    }

    // --- Reminder ---

    @Test
    fun `classify set reminder`() {
        val result = classifier.classify("Напомни через час")
        assertEquals(IntentType.SET_REMINDER, result.type)
    }

    // --- Translate ---

    @Test
    fun `classify translate`() {
        val result = classifier.classify("Переведи это предложение")
        assertEquals(IntentType.TRANSLATE, result.type)
    }

    // --- Web Search ---

    @Test
    fun `classify web search - загугли`() {
        val result = classifier.classify("Загугли погоду в Москве")
        assertEquals(IntentType.WEB_SEARCH, result.type)
        assertEquals("погоду в москве", result.slots["query"])
    }

    // --- Memory ---

    @Test
    fun `classify memory query`() {
        val result = classifier.classify("Где я припарковал машину?")
        assertEquals(IntentType.MEMORY_QUERY, result.type)
    }

    // --- Repeat ---

    @Test
    fun `classify repeat last`() {
        val result = classifier.classify("Повтори")
        assertEquals(IntentType.REPEAT_LAST, result.type)
    }

    // --- General ---

    @Test
    fun `classify general question - unknown text`() {
        val result = classifier.classify("Какая столица Франции?")
        assertEquals(IntentType.GENERAL_QUESTION, result.type)
        assertEquals(0.5f, result.confidence, 0.01f)
    }

    // --- Edge cases ---

    @Test
    fun `classify empty string returns general`() {
        val result = classifier.classify("")
        assertEquals(IntentType.GENERAL_QUESTION, result.type)
    }

    @Test
    fun `classify is case insensitive`() {
        val result = classifier.classify("ПОЗВОНИ МАМЕ")
        assertEquals(IntentType.CALL_CONTACT, result.type)
    }

    @Test
    fun `slot extraction returns null when no contact after keyword`() {
        val result = classifier.classify("Позвони")
        assertEquals(IntentType.CALL_CONTACT, result.type)
        assertNull(result.slots["contact"])
    }
}
