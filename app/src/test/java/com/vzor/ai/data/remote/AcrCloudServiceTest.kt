package com.vzor.ai.data.remote

import org.junit.Assert.*
import org.junit.Test

class AcrCloudServiceTest {

    @Test
    fun `parseResponse with successful match`() {
        val json = """
        {
            "status": {"code": 0, "msg": "Success"},
            "metadata": {
                "music": [{
                    "title": "Bohemian Rhapsody",
                    "artists": [{"name": "Queen"}],
                    "album": {"name": "A Night at the Opera"},
                    "release_date": "1975-10-31",
                    "duration_ms": 354000,
                    "score": 100,
                    "genres": [{"name": "Rock"}, {"name": "Classic Rock"}]
                }]
            }
        }
        """.trimIndent()

        val service = createService()
        val result = service.parseResponse(json)

        assertNotNull(result)
        assertEquals("Bohemian Rhapsody", result!!.title)
        assertEquals("Queen", result.artist)
        assertEquals("A Night at the Opera", result.album)
        assertEquals("1975-10-31", result.releaseDate)
        assertEquals(354000L, result.durationMs)
        assertEquals(100, result.score)
        assertEquals(2, result.genres.size)
        assertEquals("Rock", result.genres[0])
    }

    @Test
    fun `parseResponse with no match returns null`() {
        val json = """{"status": {"code": 1001, "msg": "No result"}}"""
        val service = createService()
        val result = service.parseResponse(json)
        assertNull(result)
    }

    @Test
    fun `parseResponse with empty music array returns null`() {
        val json = """
        {
            "status": {"code": 0, "msg": "Success"},
            "metadata": {"music": []}
        }
        """.trimIndent()

        val service = createService()
        assertNull(service.parseResponse(json))
    }

    @Test
    fun `parseResponse with minimal track data`() {
        val json = """
        {
            "status": {"code": 0, "msg": "Success"},
            "metadata": {
                "music": [{
                    "title": "Unknown Track",
                    "score": 50
                }]
            }
        }
        """.trimIndent()

        val service = createService()
        val result = service.parseResponse(json)

        assertNotNull(result)
        assertEquals("Unknown Track", result!!.title)
        assertEquals("Unknown", result.artist)
        assertNull(result.album)
        assertEquals(50, result.score)
        assertTrue(result.genres.isEmpty())
    }

    @Test
    fun `parseResponse with invalid JSON returns null`() {
        val service = createService()
        assertNull(service.parseResponse("not valid json"))
    }

    @Test
    fun `generateSignature produces non-empty result`() {
        val service = createService()
        val signature = service.generateSignature(
            accessKey = "test-key",
            accessSecret = "test-secret",
            dataType = "audio",
            signatureVersion = "1",
            timestamp = "1609459200"
        )
        assertTrue(signature.isNotBlank())
    }

    @Test
    fun `generateSignature is deterministic`() {
        val service = createService()
        val sig1 = service.generateSignature("key", "secret", "audio", "1", "123")
        val sig2 = service.generateSignature("key", "secret", "audio", "1", "123")
        assertEquals(sig1, sig2)
    }

    @Test
    fun `generateSignature changes with different timestamp`() {
        val service = createService()
        val sig1 = service.generateSignature("key", "secret", "audio", "1", "123")
        val sig2 = service.generateSignature("key", "secret", "audio", "1", "456")
        assertNotEquals(sig1, sig2)
    }

    @Test
    fun `formatForUser contains title and artist`() {
        val result = AcrCloudService.MusicRecognitionResult(
            title = "Song",
            artist = "Artist",
            album = "Album",
            releaseDate = "2024",
            durationMs = 180000,
            genres = listOf("Pop"),
            score = 90
        )
        val formatted = result.formatForUser()
        assertTrue(formatted.contains("Song"))
        assertTrue(formatted.contains("Artist"))
        assertTrue(formatted.contains("Album"))
        assertTrue(formatted.contains("Pop"))
    }

    @Test
    fun `RECOMMENDED_AUDIO_DURATION_MS is 8 seconds`() {
        assertEquals(8000L, AcrCloudService.RECOMMENDED_AUDIO_DURATION_MS)
    }

    @Test
    fun `MAX_AUDIO_BYTES is 320KB`() {
        assertEquals(320_000, AcrCloudService.MAX_AUDIO_BYTES)
    }

    /**
     * Создаём AcrCloudService с mock-зависимостями для тестирования парсинга.
     * PreferencesManager требует Android Context, поэтому используем reflection.
     */
    private fun createService(): AcrCloudService {
        // AcrCloudService requires Android Context through PreferencesManager,
        // but parseResponse and generateSignature are internal and work without it.
        // Using a constructor that allows testing parse/signature methods.
        return AcrCloudService::class.java.getDeclaredConstructor(
            okhttp3.OkHttpClient::class.java,
            com.vzor.ai.data.local.PreferencesManager::class.java
        ).also { it.isAccessible = true }.newInstance(
            okhttp3.OkHttpClient(),
            null // PreferencesManager is null — only parse/signature methods are tested
        )
    }
}
