package com.vzor.ai.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHelperTest {

    @Test
    fun `isAccessibilityQuery detects scene description requests`() {
        assertTrue(AccessibilityHelper.isAccessibilityQuery("что вокруг меня"))
        assertTrue(AccessibilityHelper.isAccessibilityQuery("опиши окружение"))
    }

    @Test
    fun `isAccessibilityQuery detects navigation assistance`() {
        assertTrue(AccessibilityHelper.isAccessibilityQuery("помоги пройти"))
        assertTrue(AccessibilityHelper.isAccessibilityQuery("что впереди"))
        assertTrue(AccessibilityHelper.isAccessibilityQuery("безопасно ли идти"))
    }

    @Test
    fun `isAccessibilityQuery detects reading requests`() {
        assertTrue(AccessibilityHelper.isAccessibilityQuery("прочитай вслух"))
        assertTrue(AccessibilityHelper.isAccessibilityQuery("прочитай для меня"))
    }

    @Test
    fun `isAccessibilityQuery detects object identification`() {
        assertTrue(AccessibilityHelper.isAccessibilityQuery("что я держу"))
        assertTrue(AccessibilityHelper.isAccessibilityQuery("что в руке"))
    }

    @Test
    fun `isAccessibilityQuery rejects non-accessibility queries`() {
        assertFalse(AccessibilityHelper.isAccessibilityQuery("сколько калорий"))
        assertFalse(AccessibilityHelper.isAccessibilityQuery("позвони маме"))
    }

    @Test
    fun `buildSceneDescriptionPrompt includes directions`() {
        val prompt = AccessibilityHelper.buildSceneDescriptionPrompt()
        assertTrue(prompt.contains("слева"))
        assertTrue(prompt.contains("препятстви"))
    }

    @Test
    fun `buildNavigationAssistPrompt includes safety`() {
        val prompt = AccessibilityHelper.buildNavigationAssistPrompt()
        assertTrue(prompt.contains("безопасно"))
        assertTrue(prompt.contains("ступеньки"))
    }

    @Test
    fun `buildObjectIdentificationPrompt includes user query`() {
        val prompt = AccessibilityHelper.buildObjectIdentificationPrompt("что за лекарство")
        assertTrue(prompt.contains("что за лекарство"))
        assertTrue(prompt.contains("лекарство"))
    }
}
