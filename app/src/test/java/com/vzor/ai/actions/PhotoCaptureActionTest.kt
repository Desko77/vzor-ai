package com.vzor.ai.actions

import com.vzor.ai.glasses.GlassesManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PhotoCaptureActionTest {

    private lateinit var glassesManager: GlassesManager
    private lateinit var action: PhotoCaptureAction

    @Before
    fun setup() {
        glassesManager = mockk()
        action = PhotoCaptureAction(glassesManager)
    }

    @Test
    fun `capture returns success when photo bytes available`() = runTest {
        every { glassesManager.isCameraAvailable() } returns true
        coEvery { glassesManager.capturePhoto() } returns ByteArray(50_000) // ~50KB

        val result = action.capture()

        assertTrue(result.success)
        assertTrue(result.message.contains("Фото сделано"))
        assertTrue(result.message.contains("КБ"))
    }

    @Test
    fun `capture returns failure when camera not available`() = runTest {
        every { glassesManager.isCameraAvailable() } returns false

        val result = action.capture()

        assertFalse(result.success)
        assertTrue(result.message.contains("Камера очков недоступна"))
    }

    @Test
    fun `capture returns failure when photo is null`() = runTest {
        every { glassesManager.isCameraAvailable() } returns true
        coEvery { glassesManager.capturePhoto() } returns null

        val result = action.capture()

        assertFalse(result.success)
        assertTrue(result.message.contains("Не удалось сделать фото"))
    }

    @Test
    fun `capture returns failure when exception thrown`() = runTest {
        every { glassesManager.isCameraAvailable() } returns true
        coEvery { glassesManager.capturePhoto() } throws RuntimeException("Camera error")

        val result = action.capture()

        assertFalse(result.success)
        assertTrue(result.message.contains("Ошибка камеры"))
    }

    @Test
    fun `capture formats large file size as MB`() = runTest {
        every { glassesManager.isCameraAvailable() } returns true
        coEvery { glassesManager.capturePhoto() } returns ByteArray(2_500_000) // ~2.5MB

        val result = action.capture()

        assertTrue(result.success)
        assertTrue(result.message.contains("МБ"))
    }
}
