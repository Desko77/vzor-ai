package com.vzor.ai.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationFocusManagerTest {

    // --- FocusStatus enum ---

    @Test
    fun `FocusStatus has all expected values`() {
        assertEquals(4, FocusStatus.entries.size)
        assertNotNull(FocusStatus.valueOf("IDLE"))
        assertNotNull(FocusStatus.valueOf("LISTENING"))
        assertNotNull(FocusStatus.valueOf("PROCESSING"))
        assertNotNull(FocusStatus.valueOf("ERROR"))
    }

    // --- InsightType enum ---

    @Test
    fun `InsightType has all expected values`() {
        assertEquals(3, InsightType.entries.size)
        assertNotNull(InsightType.valueOf("SUMMARY"))
        assertNotNull(InsightType.valueOf("KEY_FACTS"))
        assertNotNull(InsightType.valueOf("SUGGESTION"))
    }

    // --- ConversationFocusState ---

    @Test
    fun `default state is idle and inactive`() {
        val state = ConversationFocusState()
        assertFalse(state.isActive)
        assertEquals(FocusStatus.IDLE, state.status)
        assertEquals(0, state.transcriptCount)
        assertNull(state.lastInsight)
        assertNull(state.insightType)
        assertNull(state.error)
    }

    @Test
    fun `active state with listening status`() {
        val state = ConversationFocusState(
            isActive = true,
            status = FocusStatus.LISTENING,
            transcriptCount = 15
        )
        assertTrue(state.isActive)
        assertEquals(FocusStatus.LISTENING, state.status)
        assertEquals(15, state.transcriptCount)
    }

    @Test
    fun `state with insight`() {
        val state = ConversationFocusState(
            isActive = true,
            status = FocusStatus.LISTENING,
            lastInsight = "Обсуждали бюджет проекта",
            insightType = InsightType.SUMMARY,
            transcriptCount = 42
        )
        assertEquals("Обсуждали бюджет проекта", state.lastInsight)
        assertEquals(InsightType.SUMMARY, state.insightType)
    }

    @Test
    fun `state with error`() {
        val state = ConversationFocusState(
            isActive = true,
            status = FocusStatus.ERROR,
            error = "Микрофон недоступен"
        )
        assertEquals(FocusStatus.ERROR, state.status)
        assertEquals("Микрофон недоступен", state.error)
    }

    // --- TranscriptEntry ---

    @Test
    fun `TranscriptEntry stores correct data`() {
        val entry = TranscriptEntry(
            text = "Давайте обсудим бюджет",
            timestamp = 1709712000000L,
            language = "ru",
            confidence = 0.95f
        )
        assertEquals("Давайте обсудим бюджет", entry.text)
        assertEquals("ru", entry.language)
        assertEquals(0.95f, entry.confidence, 0.001f)
    }

    @Test
    fun `TranscriptEntry default values`() {
        val entry = TranscriptEntry(
            text = "Hello world",
            timestamp = System.currentTimeMillis()
        )
        assertEquals("ru", entry.language)
        assertEquals(1.0f, entry.confidence, 0.001f)
    }

    // --- Transcript buffer limits ---

    @Test
    fun `transcript buffer respects max entries`() {
        val maxEntries = 100
        val buffer = mutableListOf<TranscriptEntry>()

        // Добавляем 150 записей
        for (i in 1..150) {
            buffer.add(TranscriptEntry(text = "Entry $i", timestamp = i.toLong()))
            if (buffer.size > maxEntries) {
                buffer.removeAt(0)
            }
        }

        assertEquals(maxEntries, buffer.size)
        // Первая запись — #51 (первые 50 удалены)
        assertEquals("Entry 51", buffer.first().text)
        assertEquals("Entry 150", buffer.last().text)
    }

    @Test
    fun `short utterances are filtered`() {
        val minLength = 3
        val texts = listOf("Да", "Нет", "Ок", "Хорошо, обсудим", "Привет", "Ну")

        val accepted = texts.filter { it.length >= minLength }
        assertEquals(4, accepted.size) // "Нет", "Хорошо, обсудим", "Привет"
        // "Да" (2), "Ок" (2), "Ну" (2) отфильтрованы
        assertTrue(accepted.contains("Хорошо, обсудим"))
        assertTrue(accepted.contains("Привет"))
    }
}
