package com.vzor.ai.orchestrator

import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.VzorIntent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rule-based MVP intent classifier.
 * Classifies Russian-language user transcripts into [VzorIntent]s
 * using keyword matching. Will be replaced by an ML model in later sprints.
 */
@Singleton
class IntentClassifier @Inject constructor() {

    /**
     * Classify a user transcript into a [VzorIntent].
     *
     * @param transcript The raw STT transcript (Russian text).
     * @return Classified intent with type, confidence, slots, and flags.
     */
    fun classify(transcript: String): VzorIntent {
        val lower = transcript.lowercase().trim()

        return when {
            // Vision
            lower.containsAny("что видишь", "что это", "посмотри", "опиши что", "прочитай") ->
                VzorIntent(IntentType.VISION_QUERY, 0.9f, requiresVision = true)

            // Call
            lower.containsAny("позвони", "набери", "вызови") -> {
                val contact = extractContact(lower, listOf("позвони", "набери", "вызови"))
                VzorIntent(
                    type = IntentType.CALL_CONTACT,
                    confidence = 0.85f,
                    slots = buildSlots("contact" to contact),
                    requiresConfirmation = true
                )
            }

            // Message
            lower.containsAny("напиши", "отправь сообщение", "скажи в") -> {
                val contact = extractContact(lower, listOf("напиши", "отправь сообщение", "скажи в"))
                VzorIntent(
                    type = IntentType.SEND_MESSAGE,
                    confidence = 0.85f,
                    slots = buildSlots("contact" to contact),
                    requiresConfirmation = true
                )
            }

            // Music
            lower.containsAny("включи музыку", "поставь", "следующий трек", "пауза") ->
                VzorIntent(IntentType.PLAY_MUSIC, 0.8f)

            // Navigate
            lower.containsAny("навигация", "маршрут", "как доехать", "как пройти") -> {
                val destination = extractAfterKeyword(lower, listOf("навигация", "маршрут до", "как доехать до", "как пройти до", "маршрут", "как доехать", "как пройти"))
                VzorIntent(
                    type = IntentType.NAVIGATE,
                    confidence = 0.8f,
                    slots = buildSlots("destination" to destination)
                )
            }

            // Remind
            lower.containsAny("напомни", "таймер", "будильник") ->
                VzorIntent(IntentType.SET_REMINDER, 0.8f)

            // Translate
            lower.containsAny("переведи", "перевод", "режим перевода") ->
                VzorIntent(IntentType.TRANSLATE, 0.85f)

            // Web Search
            lower.containsAny("найди в интернете", "загугли", "поищи") -> {
                val query = extractAfterKeyword(lower, listOf("найди в интернете", "загугли", "поищи"))
                VzorIntent(
                    type = IntentType.WEB_SEARCH,
                    confidence = 0.8f,
                    slots = buildSlots("query" to query)
                )
            }

            // Memory
            lower.containsAny("где я припарковал", "что ты запомнил", "что я говорил") ->
                VzorIntent(IntentType.MEMORY_QUERY, 0.8f)

            // Repeat
            lower.containsAny("повтори", "что ты сказал", "ещё раз") ->
                VzorIntent(IntentType.REPEAT_LAST, 0.9f)

            // Default — general question
            else -> VzorIntent(IntentType.GENERAL_QUESTION, 0.5f)
        }
    }

    /**
     * Extract a contact name that follows a trigger keyword.
     * E.g. "позвони маме" → "маме", "набери Сашу" → "Сашу"
     */
    private fun extractContact(text: String, keywords: List<String>): String? {
        for (keyword in keywords) {
            val index = text.indexOf(keyword)
            if (index >= 0) {
                val afterKeyword = text.substring(index + keyword.length).trim()
                if (afterKeyword.isNotBlank()) {
                    return afterKeyword.split(" ").take(3).joinToString(" ")
                }
            }
        }
        return null
    }

    /**
     * Extract text following a trigger keyword for slots like destination, query, etc.
     */
    private fun extractAfterKeyword(text: String, keywords: List<String>): String? {
        // Try longest keywords first for more specific matches
        for (keyword in keywords.sortedByDescending { it.length }) {
            val index = text.indexOf(keyword)
            if (index >= 0) {
                val afterKeyword = text.substring(index + keyword.length).trim()
                if (afterKeyword.isNotBlank()) {
                    return afterKeyword
                }
            }
        }
        return null
    }

    /**
     * Build a slot map, filtering out null values.
     */
    private fun buildSlots(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs.mapNotNull { (key, value) ->
            value?.let { key to it }
        }.toMap()
    }

    /**
     * Extension function: returns true if the string contains any of the given substrings.
     */
    private fun String.containsAny(vararg keywords: String): Boolean {
        return keywords.any { this.contains(it) }
    }
}
