package com.vzor.ai.vision

import org.junit.Assert.assertNotNull
import org.junit.Test

class SharedImageHandlerTest {

    @Test
    fun `sharedImages flow is available`() {
        val handler = SharedImageHandler()
        assertNotNull(handler.sharedImages)
    }
}
