package com.vzor.ai.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingComparisonHelperTest {

    @Test
    fun `isShoppingQuery detects price questions`() {
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("сколько стоит"))
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("какая цена"))
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("прочитай ценник"))
    }

    @Test
    fun `isShoppingQuery detects comparison queries`() {
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("сравни товары"))
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("что лучше"))
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("какой лучше"))
    }

    @Test
    fun `isShoppingQuery detects shopping context`() {
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("стоит ли покупать"))
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("есть скидка"))
        assertTrue(ShoppingComparisonHelper.isShoppingQuery("прочитай этикетку"))
    }

    @Test
    fun `isShoppingQuery rejects non-shopping queries`() {
        assertFalse(ShoppingComparisonHelper.isShoppingQuery("что ты видишь"))
        assertFalse(ShoppingComparisonHelper.isShoppingQuery("позвони маме"))
        assertFalse(ShoppingComparisonHelper.isShoppingQuery("переведи текст"))
    }

    @Test
    fun `buildProductAnalysisPrompt includes user query`() {
        val prompt = ShoppingComparisonHelper.buildProductAnalysisPrompt("что за телефон")
        assertTrue(prompt.contains("что за телефон"))
        assertTrue(prompt.contains("Товар"))
    }

    @Test
    fun `buildComparisonPrompt mentions comparison criteria`() {
        val prompt = ShoppingComparisonHelper.buildComparisonPrompt()
        assertTrue(prompt.contains("Цена"))
        assertTrue(prompt.contains("рекомендацию"))
    }

    @Test
    fun `buildWebSearchQuery includes product name`() {
        val query = ShoppingComparisonHelper.buildWebSearchQuery("iPhone 16")
        assertTrue(query.contains("iPhone 16"))
        assertTrue(query.contains("цена"))
    }

    @Test
    fun `buildAlternativesQuery includes product name`() {
        val query = ShoppingComparisonHelper.buildAlternativesQuery("Samsung S25")
        assertTrue(query.contains("Samsung S25"))
        assertTrue(query.contains("аналоги"))
    }
}
