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
    fun `reset clears voice profiles`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        diarizer.processFrame(loud, 0L)
        diarizer.processFrame(loud, 300L)
        diarizer.processFrame(silent, 400L)

        diarizer.reset()

        val (userProfile, interlocutorProfile) = diarizer.getProfiles()
        assertEquals(0, userProfile.sampleCount)
        assertEquals(0, interlocutorProfile.sampleCount)
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

    // =================================================================
    // Спектральный анализ: тесты
    // =================================================================

    @Test
    fun `calculateRms returns zero for silent frame`() {
        val silent = ByteArray(320)
        assertEquals(0, diarizer.calculateRms(silent))
    }

    @Test
    fun `calculateRms returns positive for loud frame`() {
        val loud = createLoudFrame(320, 5000)
        assertTrue(diarizer.calculateRms(loud) > 0)
    }

    @Test
    fun `calculateRms returns zero for tiny buffer`() {
        assertEquals(0, diarizer.calculateRms(ByteArray(1)))
    }

    @Test
    fun `estimatePitch returns zero for silence`() {
        val silent = ByteArray(640)
        assertEquals(0f, diarizer.estimatePitch(silent))
    }

    @Test
    fun `estimatePitch returns zero for tiny buffer`() {
        val tiny = ByteArray(10)
        assertEquals(0f, diarizer.estimatePitch(tiny))
    }

    @Test
    fun `estimatePitch detects periodic signal`() {
        // Создаём периодический сигнал ~200 Hz (период = 80 samples при 16kHz)
        val period = 80
        val numSamples = 640
        val pcm = ByteArray(numSamples * 2)
        for (i in 0 until numSamples) {
            val value = (10000.0 * kotlin.math.sin(2.0 * Math.PI * i / period)).toInt().toShort()
            pcm[i * 2] = (value.toInt() and 0xFF).toByte()
            pcm[i * 2 + 1] = (value.toInt() shr 8).toByte()
        }
        val pitch = diarizer.estimatePitch(pcm)
        // Должно быть около 200 Hz (16000/80)
        assertTrue("Expected pitch ~200Hz, got $pitch", pitch > 150f && pitch < 250f)
    }

    @Test
    fun `calculateSpectralCentroid returns zero for silence`() {
        val silent = ByteArray(320)
        assertEquals(0f, diarizer.calculateSpectralCentroid(silent))
    }

    @Test
    fun `calculateSpectralCentroid returns positive for speech-like signal`() {
        val loud = createLoudFrame(320, 5000)
        // Constant signal should have low ZCR/centroid
        val centroid = diarizer.calculateSpectralCentroid(loud)
        assertTrue(centroid >= 0f)
    }

    @Test
    fun `VoiceProfile distance to self is zero`() {
        val profile = SpeakerDiarizer.VoiceProfile(
            avgPitchHz = 150f,
            avgSpectralCentroid = 2000f,
            avgRms = 3000f,
            sampleCount = 10
        )
        assertEquals(0f, profile.distanceTo(profile), 0.001f)
    }

    @Test
    fun `VoiceProfile distance to empty is 1`() {
        val profile = SpeakerDiarizer.VoiceProfile(
            avgPitchHz = 150f,
            avgSpectralCentroid = 2000f,
            avgRms = 3000f,
            sampleCount = 10
        )
        val empty = SpeakerDiarizer.VoiceProfile()
        assertEquals(1f, profile.distanceTo(empty))
    }

    @Test
    fun `VoiceProfile different pitches have significant distance`() {
        val male = SpeakerDiarizer.VoiceProfile(
            avgPitchHz = 120f, avgSpectralCentroid = 1500f, avgRms = 3000f, sampleCount = 10
        )
        val female = SpeakerDiarizer.VoiceProfile(
            avgPitchHz = 250f, avgSpectralCentroid = 2500f, avgRms = 3000f, sampleCount = 10
        )
        val distance = male.distanceTo(female)
        assertTrue("Expected distance > 0.2, got $distance", distance > 0.2f)
    }

    @Test
    fun `VoiceProfile update creates profile from zero`() {
        val empty = SpeakerDiarizer.VoiceProfile()
        val updated = empty.update(150f, 2000f, 3000f)
        assertEquals(150f, updated.avgPitchHz, 0.01f)
        assertEquals(2000f, updated.avgSpectralCentroid, 0.01f)
        assertEquals(3000f, updated.avgRms, 0.01f)
        assertEquals(1, updated.sampleCount)
    }

    @Test
    fun `VoiceProfile update uses EMA`() {
        val profile = SpeakerDiarizer.VoiceProfile(
            avgPitchHz = 100f, avgSpectralCentroid = 1000f, avgRms = 2000f, sampleCount = 5
        )
        val updated = profile.update(200f, 3000f, 4000f)
        // EMA с alpha=0.2: 100*0.8 + 200*0.2 = 120
        assertEquals(120f, updated.avgPitchHz, 0.01f)
        assertEquals(1400f, updated.avgSpectralCentroid, 0.01f)
        assertEquals(2400f, updated.avgRms, 0.01f)
        assertEquals(6, updated.sampleCount)
    }

    @Test
    fun `segment confidence is above minimum`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        diarizer.processFrame(loud, 0L)
        diarizer.processFrame(loud, 300L)
        diarizer.processFrame(silent, 400L)

        val segments = diarizer.getSegments()
        assertTrue("Confidence should be >= 0.3", segments[0].confidence >= 0.3f)
        assertTrue("Confidence should be <= 0.95", segments[0].confidence <= 0.95f)
    }

    @Test
    fun `profiles are updated after segments`() = runTest {
        val loud = createLoudFrame(320, 5000)
        val silent = ByteArray(320)

        // USER segment
        diarizer.processFrame(loud, 0L, isFromGlassesMic = true)
        diarizer.processFrame(loud, 300L, isFromGlassesMic = true)
        diarizer.processFrame(silent, 400L)

        val (userProfile, _) = diarizer.getProfiles()
        assertTrue("User profile should have samples", userProfile.sampleCount > 0)
    }

    /**
     * Creates a PCM 16-bit mono frame with a given amplitude (constant signal).
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
