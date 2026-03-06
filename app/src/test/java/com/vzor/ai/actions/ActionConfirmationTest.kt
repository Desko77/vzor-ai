package com.vzor.ai.actions

import com.vzor.ai.domain.model.IntentType
import com.vzor.ai.domain.model.VzorIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ActionConfirmationTest {

    private lateinit var confirmation: ActionConfirmation

    @Before
    fun setUp() {
        confirmation = ActionConfirmation()
    }

    private fun intent(type: IntentType, slots: Map<String, String> = emptyMap()) =
        VzorIntent(type = type, slots = slots, confidence = 1.0f)

    @Test
    fun `CALL_CONTACT requires confirmation`() {
        assertTrue(confirmation.requiresConfirmation(intent(IntentType.CALL_CONTACT)))
    }

    @Test
    fun `SEND_MESSAGE requires confirmation`() {
        assertTrue(confirmation.requiresConfirmation(intent(IntentType.SEND_MESSAGE)))
    }

    @Test
    fun `NAVIGATE requires confirmation`() {
        assertTrue(confirmation.requiresConfirmation(intent(IntentType.NAVIGATE)))
    }

    @Test
    fun `PLAY_MUSIC does not require confirmation`() {
        assertFalse(confirmation.requiresConfirmation(intent(IntentType.PLAY_MUSIC)))
    }

    @Test
    fun `SET_REMINDER does not require confirmation`() {
        assertFalse(confirmation.requiresConfirmation(intent(IntentType.SET_REMINDER)))
    }

    @Test
    fun `confirm resolves pending action to true`() = runTest {
        val callIntent = intent(IntentType.CALL_CONTACT, mapOf("contact" to "Мама"))

        val result = async { confirmation.requestConfirmation(callIntent) }

        // Verify pending action is set
        assertNotNull(confirmation.pendingAction.value)
        assertTrue(confirmation.hasPendingAction())
        assertTrue(confirmation.pendingAction.value!!.description.contains("Мама"))

        confirmation.confirm()
        assertTrue(result.await())
    }

    @Test
    fun `deny resolves pending action to false`() = runTest {
        val callIntent = intent(IntentType.CALL_CONTACT, mapOf("contact" to "Работа"))

        val result = async { confirmation.requestConfirmation(callIntent) }
        confirmation.deny()
        assertFalse(result.await())
    }

    @Test
    fun `pending action cleared after resolution`() = runTest {
        val msgIntent = intent(IntentType.SEND_MESSAGE, mapOf("contact" to "Петя"))

        val result = async { confirmation.requestConfirmation(msgIntent) }
        confirmation.confirm()
        result.await()

        assertNull(confirmation.pendingAction.value)
        assertFalse(confirmation.hasPendingAction())
    }

    @Test
    fun `description for SEND_MESSAGE includes contact and app`() = runTest {
        val msgIntent = intent(
            IntentType.SEND_MESSAGE,
            mapOf("contact" to "Алиса", "app" to "WhatsApp")
        )

        val result = async { confirmation.requestConfirmation(msgIntent) }
        val desc = confirmation.pendingAction.value?.description ?: ""
        assertTrue(desc.contains("Алиса"))
        assertTrue(desc.contains("WhatsApp"))
        confirmation.confirm()
        result.await()
    }

    @Test
    fun `description for NAVIGATE includes destination`() = runTest {
        val navIntent = intent(IntentType.NAVIGATE, mapOf("destination" to "Домой"))

        val result = async { confirmation.requestConfirmation(navIntent) }
        val desc = confirmation.pendingAction.value?.description ?: ""
        assertTrue(desc.contains("Домой"))
        confirmation.deny()
        result.await()
    }
}
