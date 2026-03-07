package com.vzor.ai.speech

import org.junit.Assert.*
import org.junit.Test

class OfflineSttServiceTest {

    @Test
    fun `SttProvider OFFLINE exists`() {
        val provider = com.vzor.ai.domain.model.SttProvider.OFFLINE
        assertEquals("Офлайн (On-Device)", provider.displayName)
    }

    @Test
    fun `SttProvider has 4 values`() {
        val values = com.vzor.ai.domain.model.SttProvider.values()
        assertEquals(4, values.size)
        assertTrue(values.any { it.name == "OFFLINE" })
    }
}
