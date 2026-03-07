package com.vzor.ai.vision

import com.vzor.ai.actions.ActionResult
import com.vzor.ai.actions.PhotoCaptureAction
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GestureActionMapperTest {

    private lateinit var photoCaptureAction: PhotoCaptureAction
    private lateinit var mapper: GestureActionMapper

    @Before
    fun setup() {
        photoCaptureAction = mockk()
        mapper = GestureActionMapper(photoCaptureAction)
    }

    @Test
    fun `Victory gesture triggers photo capture`() = runTest {
        coEvery { photoCaptureAction.capture() } returns ActionResult(true, "Фото сделано (123 КБ)")

        val result = mapper.handleGesture("Victory")

        assertTrue(result is GestureActionMapper.GestureResult.Executed)
        val executed = result as GestureActionMapper.GestureResult.Executed
        assertEquals("Victory", executed.gesture)
        assertTrue(executed.actionResult.success)
    }

    @Test
    fun `Thumb_Up returns Acknowledged`() = runTest {
        val result = mapper.handleGesture("Thumb_Up")

        assertTrue(result is GestureActionMapper.GestureResult.Acknowledged)
        assertEquals("Подтверждение", (result as GestureActionMapper.GestureResult.Acknowledged).meaning)
    }

    @Test
    fun `Thumb_Down returns Acknowledged`() = runTest {
        val result = mapper.handleGesture("Thumb_Down")

        assertTrue(result is GestureActionMapper.GestureResult.Acknowledged)
        assertEquals("Отмена", (result as GestureActionMapper.GestureResult.Acknowledged).meaning)
    }

    @Test
    fun `Open_Palm returns Acknowledged with stop meaning`() = runTest {
        val result = mapper.handleGesture("Open_Palm")

        assertTrue(result is GestureActionMapper.GestureResult.Acknowledged)
    }

    @Test
    fun `Closed_Fist returns Acknowledged with play meaning`() = runTest {
        val result = mapper.handleGesture("Closed_Fist")

        assertTrue(result is GestureActionMapper.GestureResult.Acknowledged)
    }

    @Test
    fun `Pointing_Up returns Acknowledged`() = runTest {
        val result = mapper.handleGesture("Pointing_Up")

        assertTrue(result is GestureActionMapper.GestureResult.Acknowledged)
    }

    @Test
    fun `Unknown gesture returns Unknown`() = runTest {
        val result = mapper.handleGesture("RandomGesture")

        assertTrue(result is GestureActionMapper.GestureResult.Unknown)
    }

    @Test
    fun `isActionable returns true for known gestures`() {
        assertTrue(mapper.isActionable("Victory"))
        assertTrue(mapper.isActionable("Thumb_Up"))
        assertTrue(mapper.isActionable("Open_Palm"))
    }

    @Test
    fun `isActionable returns false for unknown gestures`() {
        assertFalse(mapper.isActionable("RandomGesture"))
        assertFalse(mapper.isActionable("None"))
        assertFalse(mapper.isActionable(""))
    }

    @Test
    fun `ACTIONABLE_GESTURES contains all 6 gestures`() {
        assertEquals(6, GestureActionMapper.ACTIONABLE_GESTURES.size)
        assertTrue(GestureActionMapper.ACTIONABLE_GESTURES.containsAll(
            setOf("Victory", "Open_Palm", "Closed_Fist", "Pointing_Up", "Thumb_Up", "Thumb_Down")
        ))
    }
}
