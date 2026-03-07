package com.vzor.ai.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodAnalysisPromptsTest {

    @Test
    fun `isFoodQuery detects calorie questions`() {
        assertTrue(FoodAnalysisPrompts.isFoodQuery("сколько калорий в этом блюде"))
        assertTrue(FoodAnalysisPrompts.isFoodQuery("Какая калорийность?"))
        assertTrue(FoodAnalysisPrompts.isFoodQuery("покажи бжу"))
    }

    @Test
    fun `isFoodQuery detects food identification`() {
        assertTrue(FoodAnalysisPrompts.isFoodQuery("что за блюдо"))
        assertTrue(FoodAnalysisPrompts.isFoodQuery("что за еда"))
        assertTrue(FoodAnalysisPrompts.isFoodQuery("из чего приготовлено"))
    }

    @Test
    fun `isFoodQuery detects ingredient queries`() {
        assertTrue(FoodAnalysisPrompts.isFoodQuery("какой состав"))
        assertTrue(FoodAnalysisPrompts.isFoodQuery("есть ли аллергены"))
        assertTrue(FoodAnalysisPrompts.isFoodQuery("содержит глютен"))
    }

    @Test
    fun `isFoodQuery rejects non-food queries`() {
        assertFalse(FoodAnalysisPrompts.isFoodQuery("что это за здание"))
        assertFalse(FoodAnalysisPrompts.isFoodQuery("позвони маме"))
        assertFalse(FoodAnalysisPrompts.isFoodQuery("какая погода"))
    }

    @Test
    fun `buildAnalysisPrompt includes user query`() {
        val prompt = FoodAnalysisPrompts.buildAnalysisPrompt("что за суп")
        assertTrue(prompt.contains("что за суп"))
        assertTrue(prompt.contains("Калории"))
        assertTrue(prompt.contains("БЖУ"))
    }

    @Test
    fun `buildQuickCaloriePrompt is concise`() {
        val prompt = FoodAnalysisPrompts.buildQuickCaloriePrompt()
        assertTrue(prompt.contains("ккал"))
    }

    @Test
    fun `buildIngredientsPrompt mentions allergens`() {
        val prompt = FoodAnalysisPrompts.buildIngredientsPrompt()
        assertTrue(prompt.contains("аллерген"))
        assertTrue(prompt.contains("глютен"))
    }
}
