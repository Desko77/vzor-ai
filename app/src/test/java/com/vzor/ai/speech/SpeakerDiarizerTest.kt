package com.vzor.ai.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SpeakerDiarizerTest {

    private lateinit var diarizer: SpeakerDiarizer

    @Before
    fun setup() {
        diarizer = SpeakerDiarizer()
    }

    @Test
    fun `initial speaker is UNKNOWN`() {
        assertEquals(SpeakerDiarizer.Speaker.UNKNOWN, diarizer.currentSpeaker.value)
    }

    @Test
    fun `silence frame does not change speaker`() = runTest {
        val silentFrame = ByteArray(320) // 10ms at 16kHz, 16-bit = 320 bytes, all zeros
        diarizer.processFrame(silentFrame, 0L)
        assertEquals(SpeakerDiarizer.Speaker.UNKNOWN, diarizer.currentSpeaker.value)
    }

    @Test
    fun `loud frame from glasses mic sets speaker to USER`() = runTest {
        val loudFrame = createLoudFrame(320, 5000)
        diarizer.processFrame(loudFrame, 0L, isFromGlassesMic = true)
        assertEquals(SpeakerDiarizer.Speaker.USER, diarizer.currentSpeaker.value)
    }

    @Test
    fun `loud frame from phone mic sets speaker to INTERLOCUTOR`() = runTest {
        val loudFrame = createLoudFrame(320, 5000)
        diarizer.processFrame(loudFrame, 0L, isFromGlassesMic = false)
        assertEquals(SpeakerDiarizer.Speaker.INTERLOCUTOR, diarizer.currentSpeaker.value)
    }

    @Test
    fun `segments are empty initially`() = runTest {
        assertTrue(diarizer.getSegments().isEmpty())
    }

    @Test
    fun `speech segment is created after silence`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        // Start speech
        diarizer.processFrame(loud, 0L)
        diarizer.processFrame(loud, 100L)
        diarizer.processFrame(loud, 200L)
        diarizer.processFrame(loud, 300L) // 300ms >= MIN_SEGMENT_DURATION

        // End speech (silence)
        diarizer.processFrame(silent, 400L)

        val segments = diarizer.getSegments()
        assertEquals(1, segments.size)
        assertEquals(SpeakerDiarizer.Speaker.USER, segments[0].speaker)
        assertEquals(0L, segments[0].startMs)
        assertEquals(400L, segments[0].endMs)
    }

    @Test
    fun `reset clears all state`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        diarizer.processFrame(loud, 0L)
        diarizer.processFrame(loud, 300L)
        diarizer.processFrame(silent, 400L)

        diarizer.reset()

        assertEquals(SpeakerDiarizer.Speaker.UNKNOWN, diarizer.currentSpeaker.value)
        assertTrue(diarizer.getSegments().isEmpty())
    }

    @Test
    fun `attachText updates last segment`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        diarizer.processFrame(loud, 0L)
        diarizer.processFrame(loud, 300L)
        diarizer.processFrame(silent, 400L)

        diarizer.attachText("Привет, как дела?")

        val segments = diarizer.getSegments()
        assertEquals("Привет, как дела?", segments.last().text)
    }

    @Test
    fun `speaker switches after long pause`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        // First speaker (USER)
        diarizer.processFrame(loud, 0L, isFromGlassesMic = true)
        diarizer.processFrame(loud, 300L)
        diarizer.processFrame(silent, 400L) // end first segment

        // Long pause (600ms > 500ms threshold)
        // Second speaker should be INTERLOCUTOR
        diarizer.processFrame(loud, 1000L, isFromGlassesMic = true)

        assertEquals(SpeakerDiarizer.Speaker.INTERLOCUTOR, diarizer.currentSpeaker.value)
    }

    /**
     * Creates a PCM 16-bit mono frame with a given amplitude (sine-like pattern).
     */
    private fun createLoudFrame(sizeBytes: Int, amplitude: Int): ByteArray {
        val frame = ByteArray(sizeBytes)
        var i = 0
        while (i < sizeBytes - 1) {
            val sample = amplitude.toShort()
            frame[i] = (sample.toInt() and 0xFF).toByte()
            frame[i + 1] = (sample.toInt() shr 8).toByte()
            i += 2
        }
        return frame
    }
}
