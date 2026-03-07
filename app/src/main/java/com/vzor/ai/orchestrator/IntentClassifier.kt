package com.vzor.ai.orchestrator

import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.VzorIntent
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Weighted scoring intent classifier with fuzzy matching.
 * Classifies Russian-language user transcripts into [VzorIntent]s
 * using weighted keywords + Levenshtein distance for typo tolerance.
 */
@Singleton
class IntentClassifier @Inject constructor() {

    companion object {
        /** Предкомпилированный regex для разбиения на слова. */
        private val WHITESPACE_REGEX = "\\s+".toRegex()
    }

    private data class WeightedKeyword(
        val keyword: String,
        val weight: Float = 1.0f,
        val fuzzyThreshold: Int = 0 // max Levenshtein distance, 0 = exact match only
    )

    private data class IntentRule(
        val intentType: IntentType,
        val keywords: List<WeightedKeyword>,
        val baseConfidence: Float,
        val requiresVision: Boolean = false,
        val requiresConfirmation: Boolean = false,
        val slotExtractor: ((String) -> Map<String, String>)?  = null
    )

    private val rules: List<IntentRule> = listOf(
        // Vision
        IntentRule(
            intentType = IntentType.VISION_QUERY,
            keywords = listOf(
                WeightedKeyword("что ты видишь", 1.0f),
                WeightedKeyword("что видишь", 1.0f),
                WeightedKeyword("что это", 0.8f),
                WeightedKeyword("посмотри", 0.9f),
                WeightedKeyword("опиши что", 0.9f),
                WeightedKeyword("прочитай", 0.8f),
                WeightedKeyword("что перед", 0.7f)
            ),
            baseConfidence = 0.9f,
            requiresVision = true
        ),
        // Call
        IntentRule(
            intentType = IntentType.CALL_CONTACT,
            keywords = listOf(
                WeightedKeyword("позвони", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("набери", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("вызови", 0.9f, fuzzyThreshold = 1)
            ),
            baseConfidence = 0.85f,
            requiresConfirmation = true,
            slotExtractor = { text -> extractSlot(text, listOf("позвони", "набери", "вызови"), "contact", fuzzyThreshold = 1) }
        ),
        // Message
        IntentRule(
            intentType = IntentType.SEND_MESSAGE,
            keywords = listOf(
                WeightedKeyword("напиши", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("отправь сообщение", 1.0f),
                WeightedKeyword("скажи в", 0.8f)
            ),
            baseConfidence = 0.85f,
            requiresConfirmation = true,
            slotExtractor = { text -> extractSlot(text, listOf("напиши", "отправь сообщение", "скажи в"), "contact", fuzzyThreshold = 1) }
        ),
        // Music
        IntentRule(
            intentType = IntentType.PLAY_MUSIC,
            keywords = listOf(
                WeightedKeyword("включи музыку", 1.0f),
                WeightedKeyword("поставь", 0.8f),
                WeightedKeyword("следующий трек", 0.9f),
                WeightedKeyword("пауза", 0.9f)
            ),
            baseConfidence = 0.8f
        ),
        // Navigate
        IntentRule(
            intentType = IntentType.NAVIGATE,
            keywords = listOf(
                WeightedKeyword("навигация", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("маршрут", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("как доехать", 0.9f),
                WeightedKeyword("как пройти", 0.9f)
            ),
            baseConfidence = 0.8f,
            slotExtractor = { text ->
                extractSlot(
                    text,
                    listOf("навигация", "маршрут до", "как доехать до", "как пройти до", "маршрут", "как доехать", "как пройти"),
                    "destination"
                )
            }
        ),
        // Reminder
        IntentRule(
            intentType = IntentType.SET_REMINDER,
            keywords = listOf(
                WeightedKeyword("напомни", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("таймер", 0.9f),
                WeightedKeyword("будильник", 0.9f)
            ),
            baseConfidence = 0.8f
        ),
        // Translate
        IntentRule(
            intentType = IntentType.TRANSLATE,
            keywords = listOf(
                WeightedKeyword("переведи", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("перевод", 0.9f),
                WeightedKeyword("режим перевода", 1.0f)
            ),
            baseConfidence = 0.85f
        ),
        // Web Search
        IntentRule(
            intentType = IntentType.WEB_SEARCH,
            keywords = listOf(
                WeightedKeyword("найди в интернете", 1.0f),
                WeightedKeyword("загугли", 1.0f),
                WeightedKeyword("поищи", 0.9f)
            ),
            baseConfidence = 0.8f,
            slotExtractor = { text ->
                extractSlot(text, listOf("найди в интернете", "загугли", "поищи"), "query")
            }
        ),
        // Memory
        IntentRule(
            intentType = IntentType.MEMORY_QUERY,
            keywords = listOf(
                WeightedKeyword("где я припарковал", 1.0f),
                WeightedKeyword("что ты запомнил", 0.9f),
                WeightedKeyword("что я говорил", 0.8f)
            ),
            baseConfidence = 0.8f
        ),
        // Repeat
        IntentRule(
            intentType = IntentType.REPEAT_LAST,
            keywords = listOf(
                WeightedKeyword("повтори", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("что ты сказал", 0.9f),
                WeightedKeyword("ещё раз", 0.9f)
            ),
            baseConfidence = 0.9f
        ),
        // Capture Photo (UC#11: фото hands-free)
        IntentRule(
            intentType = IntentType.CAPTURE_PHOTO,
            keywords = listOf(
                WeightedKeyword("сфотографируй", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("сделай фото", 1.0f),
                WeightedKeyword("сделай снимок", 1.0f),
                WeightedKeyword("фотка", 0.8f),
                WeightedKeyword("щёлкни", 0.8f, fuzzyThreshold = 1)
            ),
            baseConfidence = 0.9f,
            requiresVision = true
        ),
        // Live Commentary (UC#6: непрерывный AI-комментарий)
        IntentRule(
            intentType = IntentType.LIVE_COMMENTARY,
            keywords = listOf(
                WeightedKeyword("включи комментарий", 1.0f),
                WeightedKeyword("режим наблюдения", 1.0f),
                WeightedKeyword("комментируй", 1.0f, fuzzyThreshold = 1),
                WeightedKeyword("что вокруг", 0.8f),
                WeightedKeyword("выключи комментарий", 1.0f),
                WeightedKeyword("хватит комментировать", 0.9f)
            ),
            baseConfidence = 0.85f,
            requiresVision = true
        ),
        // Conversation Focus (UC#13: режим фокуса на разговоре)
        IntentRule(
            intentType = IntentType.CONVERSATION_FOCUS,
            keywords = listOf(
                WeightedKeyword("режим фокуса", 1.0f),
                WeightedKeyword("слушай разговор", 1.0f),
                WeightedKeyword("саммари разговора", 0.9f),
                WeightedKeyword("что обсуждали", 0.8f),
                WeightedKeyword("ключевые моменты", 0.8f),
                WeightedKeyword("выключи фокус", 1.0f)
            ),
            baseConfidence = 0.85f
        ),
        // Food Analysis (UC#4: анализ еды и калорийности)
        IntentRule(
            intentType = IntentType.FOOD_ANALYSIS,
            keywords = listOf(
                WeightedKeyword("сколько калорий", 1.0f),
                WeightedKeyword("калорийность", 1.0f),
                WeightedKeyword("что за блюдо", 1.0f),
                WeightedKeyword("что за еда", 0.9f),
                WeightedKeyword("из чего приготовлено", 0.9f),
                WeightedKeyword("бжу", 0.9f),
                WeightedKeyword("состав блюда", 0.9f)
            ),
            baseConfidence = 0.85f,
            requiresVision = true
        ),
        // Shopping Assist (UC#5: шопинг-помощник)
        IntentRule(
            intentType = IntentType.SHOPPING_ASSIST,
            keywords = listOf(
                WeightedKeyword("сколько стоит", 1.0f),
                WeightedKeyword("какая цена", 1.0f),
                WeightedKeyword("сравни товар", 1.0f),
                WeightedKeyword("что лучше купить", 0.9f),
                WeightedKeyword("прочитай ценник", 1.0f),
                WeightedKeyword("прочитай этикетку", 0.9f),
                WeightedKeyword("стоит ли покупать", 0.9f)
            ),
            baseConfidence = 0.85f,
            requiresVision = true
        ),
        // Accessibility (UC#8: Be My Eyes — помощь слабовидящим)
        IntentRule(
            intentType = IntentType.ACCESSIBILITY,
            keywords = listOf(
                WeightedKeyword("что вокруг меня", 1.0f),
                WeightedKeyword("опиши окружение", 1.0f),
                WeightedKeyword("помоги пройти", 1.0f),
                WeightedKeyword("что впереди", 0.9f),
                WeightedKeyword("есть ли препятстви", 0.9f),
                WeightedKeyword("прочитай вслух", 1.0f),
                WeightedKeyword("что я держу", 0.9f),
                WeightedKeyword("безопасно ли", 0.8f)
            ),
            baseConfidence = 0.9f,
            requiresVision = true
        ),
        // Place Identification (UC#2: идентификация мест)
        IntentRule(
            intentType = IntentType.PLACE_IDENTIFY,
            keywords = listOf(
                WeightedKeyword("что за здание", 1.0f),
                WeightedKeyword("что за место", 1.0f),
                WeightedKeyword("где я нахожусь", 1.0f),
                WeightedKeyword("достопримечательност", 0.9f),
                WeightedKeyword("что за магазин", 0.9f),
                WeightedKeyword("что за ресторан", 0.9f),
                WeightedKeyword("какое здание", 0.9f)
            ),
            baseConfidence = 0.85f,
            requiresVision = true
        )
    )

    /**
     * Classify a user transcript into a [VzorIntent].
     *
     * @param transcript The raw STT transcript (Russian text).
     * @return Classified intent with type, confidence, slots, and flags.
     */
    fun classify(transcript: String): VzorIntent {
        val lower = transcript.lowercase().trim()
        if (lower.isBlank()) {
            return VzorIntent(IntentType.GENERAL_QUESTION, 0.5f)
        }

        var bestRule: IntentRule? = null
        var bestScore = 0f

        for (rule in rules) {
            val score = scoreRule(lower, rule)
            if (score > bestScore || (score == bestScore && bestRule != null && rule.baseConfidence > bestRule.baseConfidence)) {
                bestScore = score
                bestRule = rule
            }
        }

        if (bestRule == null || bestScore < 0.5f) {
            return VzorIntent(IntentType.GENERAL_QUESTION, 0.5f)
        }

        // Confidence scales with score: base confidence boosted by multi-keyword hits
        val confidence = min(bestRule.baseConfidence + (bestScore - 1f) * 0.1f, 1.0f)
            .coerceAtLeast(bestRule.baseConfidence * 0.8f)

        val slots = bestRule.slotExtractor?.invoke(lower) ?: emptyMap()

        return VzorIntent(
            type = bestRule.intentType,
            confidence = confidence,
            slots = slots,
            requiresConfirmation = bestRule.requiresConfirmation,
            requiresVision = bestRule.requiresVision
        )
    }

    /**
     * Score a rule against a transcript. Higher = better match.
     * Returns 0 if no keyword matches.
     */
    private fun scoreRule(text: String, rule: IntentRule): Float {
        var totalScore = 0f
        for (wk in rule.keywords) {
            if (text.contains(wk.keyword)) {
                totalScore += wk.weight
            } else if (wk.fuzzyThreshold > 0 && fuzzyContains(text, wk.keyword, wk.fuzzyThreshold)) {
                totalScore += wk.weight * 0.8f // Fuzzy match gets 80% weight
            }
        }
        return totalScore
    }

    /**
     * Check if text contains a fuzzy match for the keyword (word-level).
     * Splits both text and keyword into words and checks if any contiguous
     * subsequence of text words matches the keyword words within the Levenshtein threshold.
     */
    private fun fuzzyContains(text: String, keyword: String, threshold: Int): Boolean {
        val textWords = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val kwWords = keyword.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }

        if (kwWords.isEmpty() || textWords.size < kwWords.size) return false

        // For single-word keywords, check each text word
        if (kwWords.size == 1) {
            return textWords.any { levenshtein(it, kwWords[0]) <= threshold }
        }

        // For multi-word keywords, check contiguous subsequences
        for (i in 0..textWords.size - kwWords.size) {
            val allMatch = kwWords.indices.all { j ->
                levenshtein(textWords[i + j], kwWords[j]) <= threshold
            }
            if (allMatch) return true
        }
        return false
    }

    /**
     * Levenshtein distance between two strings. O(n*m) — acceptable for short strings.
     */
    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)

        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,     // insertion
                    prev[j] + 1,          // deletion
                    prev[j - 1] + cost    // substitution
                )
            }
            val temp = prev
            prev = curr
            curr = temp
        }
        return prev[b.length]
    }

    /**
     * Extract a slot value that follows trigger keywords.
     * Supports fuzzy word-level matching when [fuzzyThreshold] > 0.
     */
    private fun extractSlot(
        text: String,
        keywords: List<String>,
        slotName: String,
        fuzzyThreshold: Int = 0
    ): Map<String, String> {
        // Try longest keywords first for more specific matches
        for (keyword in keywords.sortedByDescending { it.length }) {
            // Exact substring match
            val index = text.indexOf(keyword)
            if (index >= 0) {
                val afterKeyword = text.substring(index + keyword.length).trim()
                if (afterKeyword.isNotBlank()) {
                    val value = if (slotName == "contact") {
                        afterKeyword.split(" ").take(3).joinToString(" ")
                    } else {
                        afterKeyword
                    }
                    return mapOf(slotName to value)
                }
            }

            // Fuzzy word-level match for single-word keywords
            if (fuzzyThreshold > 0) {
                val textWords = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val kwWords = keyword.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                if (kwWords.size == 1) {
                    val matchIdx = textWords.indexOfFirst { levenshtein(it, kwWords[0]) <= fuzzyThreshold }
                    if (matchIdx >= 0) {
                        val afterWords = textWords.subList(matchIdx + 1, textWords.size)
                        val afterText = afterWords.joinToString(" ").trim()
                        if (afterText.isNotBlank()) {
                            val value = if (slotName == "contact") {
                                afterWords.take(3).joinToString(" ")
                            } else {
                                afterText
                            }
                            return mapOf(slotName to value)
                        }
                    }
                }
            }
        }
        return emptyMap()
    }
}
