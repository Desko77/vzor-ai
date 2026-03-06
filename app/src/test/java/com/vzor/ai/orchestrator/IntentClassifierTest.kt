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

    // --- UNKNOWN type ---

    @Test
    fun `UNKNOWN type exists in IntentType enum`() {
        // Verify UNKNOWN is a valid enum value (defined but currently unused by keyword classifier)
        val unknown = IntentType.UNKNOWN
        assertNotNull(unknown)
    }

    @Test
    fun `gibberish input returns GENERAL_QUESTION as default fallback`() {
        // Current keyword-based classifier defaults to GENERAL_QUESTION for unmatched input.
        // UNKNOWN is reserved for future ML classifier when confidence is below threshold.
        val result = classifier.classify("🔥🎉🌈 zzz qqq xyzzy")
        assertEquals(IntentType.GENERAL_QUESTION, result.type)
        assertEquals(0.5f, result.confidence, 0.01f)
    }

    @Test
    fun `whitespace-only input returns GENERAL_QUESTION`() {
        val result = classifier.classify("   ")
        assertEquals(IntentType.GENERAL_QUESTION, result.type)
    }

    // --- Fuzzy matching ---

    @Test
    fun `fuzzy match - позвани (typo) still matches CALL_CONTACT`() {
        val result = classifier.classify("Позвани маме")
        assertEquals(IntentType.CALL_CONTACT, result.type)
        assertEquals("маме", result.slots["contact"])
    }

    @Test
    fun `fuzzy match - повтари (typo) still matches REPEAT_LAST`() {
        val result = classifier.classify("Повтари")
        assertEquals(IntentType.REPEAT_LAST, result.type)
    }

    @Test
    fun `fuzzy match - переведы (typo) still matches TRANSLATE`() {
        val result = classifier.classify("Переведы это")
        assertEquals(IntentType.TRANSLATE, result.type)
    }

    // --- Weighted scoring ---

    @Test
    fun `multi-keyword boost gives higher confidence`() {
        val single = classifier.classify("Позвони маме")
        val double = classifier.classify("Позвони набери маме")
        assertTrue("Double keyword should have >= confidence than single",
            double.confidence >= single.confidence)
    }

    @Test
    fun `vision query scores correctly for что ты видишь на улице`() {
        val result = classifier.classify("Что ты видишь на улице")
        assertEquals(IntentType.VISION_QUERY, result.type)
    }

    // --- Levenshtein distance ---

    @Test
    fun `levenshtein distance calculations`() {
        assertEquals(0, classifier.levenshtein("abc", "abc"))
        assertEquals(1, classifier.levenshtein("позвони", "позвани"))
        assertEquals(1, classifier.levenshtein("повтори", "повтари"))
        assertEquals(5, classifier.levenshtein("кот", "собака"))
    }

    // --- Edge cases: slot extraction ---

    @Test
    fun `slot extraction takes max 3 words for contact`() {
        val result = classifier.classify("Позвони Иван Петрович Сидоров срочно")
        assertEquals(IntentType.CALL_CONTACT, result.type)
        val contact = result.slots["contact"]
        assertNotNull(contact)
        // Should take at most 3 words after keyword
        assertTrue("Contact should be max 3 words", contact!!.split(" ").size <= 3)
    }

    @Test
    fun `позвонить contains позвони as substring — still matches`() {
        val result = classifier.classify("Надо позвонить маме")
        // "позвони" is substring of "позвонить" — fuzzy matching may pick this up
        assertEquals(IntentType.CALL_CONTACT, result.type)
    }

    @Test
    fun `fuzzy boundary - позвон (missing и) matches with Levenshtein 1`() {
        val result = classifier.classify("Позвон маме")
        // Levenshtein("позвон", "позвони") = 1, within threshold
        assertEquals(IntentType.CALL_CONTACT, result.type)
    }

    @Test
    fun `multi-word fuzzy - мршрут (typo in маршрут) matches NAVIGATE`() {
        val result = classifier.classify("Мршрут до дома")
        // Levenshtein("мршрут", "маршрут") = 1, within threshold for fuzzy
        assertEquals(IntentType.NAVIGATE, result.type)
    }

    @Test
    fun `scoring is deterministic for same input`() {
        val result1 = classifier.classify("Что видишь здесь?")
        val result2 = classifier.classify("Что видишь здесь?")
        assertEquals(result1.type, result2.type)
        assertEquals(result1.confidence, result2.confidence, 0.001f)
    }

    // --- UC#11: Capture Photo ---

    @Test
    fun `classify capture photo - сфотографируй`() {
        val result = classifier.classify("Сфотографируй")
        assertEquals(IntentType.CAPTURE_PHOTO, result.type)
        assertTrue(result.requiresVision)
    }

    @Test
    fun `classify capture photo - сделай фото`() {
        val result = classifier.classify("Сделай фото")
        assertEquals(IntentType.CAPTURE_PHOTO, result.type)
    }

    @Test
    fun `classify capture photo - сделай снимок`() {
        val result = classifier.classify("Сделай снимок этого")
        assertEquals(IntentType.CAPTURE_PHOTO, result.type)
    }

    @Test
    fun `classify capture photo - фотка`() {
        val result = classifier.classify("Фотка!")
        assertEquals(IntentType.CAPTURE_PHOTO, result.type)
    }

    @Test
    fun `fuzzy capture photo - сфатографируй (typo)`() {
        val result = classifier.classify("Сфатографируй это")
        assertEquals(IntentType.CAPTURE_PHOTO, result.type)
    }

    // --- UC#6: Live Commentary ---

    @Test
    fun `classify live commentary - включи комментарий`() {
        val result = classifier.classify("Включи комментарий")
        assertEquals(IntentType.LIVE_COMMENTARY, result.type)
        assertTrue(result.requiresVision)
    }

    @Test
    fun `classify live commentary - режим наблюдения`() {
        val result = classifier.classify("Режим наблюдения")
        assertEquals(IntentType.LIVE_COMMENTARY, result.type)
    }

    @Test
    fun `classify live commentary - выключи комментарий`() {
        val result = classifier.classify("Выключи комментарий")
        assertEquals(IntentType.LIVE_COMMENTARY, result.type)
    }

    @Test
    fun `classify live commentary - комментируй`() {
        val result = classifier.classify("Комментируй что видишь")
        assertEquals(IntentType.LIVE_COMMENTARY, result.type)
    }

    // --- UC#13: Conversation Focus ---

    @Test
    fun `classify conversation focus - режим фокуса`() {
        val result = classifier.classify("Режим фокуса")
        assertEquals(IntentType.CONVERSATION_FOCUS, result.type)
    }

    @Test
    fun `classify conversation focus - слушай разговор`() {
        val result = classifier.classify("Слушай разговор")
        assertEquals(IntentType.CONVERSATION_FOCUS, result.type)
    }

    @Test
    fun `classify conversation focus - саммари разговора`() {
        val result = classifier.classify("Саммари разговора")
        assertEquals(IntentType.CONVERSATION_FOCUS, result.type)
    }

    @Test
    fun `classify conversation focus - ключевые моменты`() {
        val result = classifier.classify("Ключевые моменты")
        assertEquals(IntentType.CONVERSATION_FOCUS, result.type)
    }

    @Test
    fun `classify conversation focus - выключи фокус`() {
        val result = classifier.classify("Выключи фокус")
        assertEquals(IntentType.CONVERSATION_FOCUS, result.type)
    }
}
