package com.vzor.ai.data.remote

import android.util.Log
import com.vzor.ai.data.local.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.InvalidKeyException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ACRCloud Audio Fingerprinting — распознавание музыки (Shazam-подобный).
 *
 * API: POST https://identify-{region}.acrcloud.com/v1/identify
 * Формат: PCM 16-bit mono → multipart/form-data с HMAC-SHA1 подписью.
 *
 * UC#16: Пользователь говорит «Что за песня?» → записываем 5-10 сек аудио →
 * отправляем на ACRCloud → получаем название, артиста, альбом.
 *
 * Требует три параметра в настройках:
 * - ACRCloud Access Key
 * - ACRCloud Access Secret
 * - ACRCloud Host (например, identify-eu-west-1.acrcloud.com)
 */
@Singleton
class AcrCloudService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val prefs: PreferencesManager
) {
    companion object {
        private const val TAG = "AcrCloudService"
        private const val API_PATH = "/v1/identify"
        private const val DATA_TYPE_AUDIO = "audio"
        private const val SIGNATURE_VERSION = "1"

        /** Рекомендуемый размер аудио для fingerprinting (5-10 сек). */
        const val RECOMMENDED_AUDIO_DURATION_MS = 8_000L

        /** Максимальный размер отправляемого аудио (10 сек * 16kHz * 2 bytes = 320KB). */
        const val MAX_AUDIO_BYTES = 320_000
    }

    /**
     * Результат распознавания музыки.
     */
    data class MusicRecognitionResult(
        val title: String,
        val artist: String,
        val album: String?,
        val releaseDate: String?,
        val durationMs: Long?,
        val genres: List<String>,
        val score: Int
    ) {
        fun formatForUser(): String {
            val sb = StringBuilder()
            sb.append("🎵 $title — $artist")
            if (!album.isNullOrBlank()) sb.append("\n📀 Альбом: $album")
            if (!releaseDate.isNullOrBlank()) sb.append("\n📅 $releaseDate")
            if (genres.isNotEmpty()) sb.append("\n🎶 ${genres.joinToString(", ")}")
            return sb.toString()
        }
    }

    /**
     * Распознаёт музыку по PCM аудио фрагменту.
     *
     * @param pcmAudio PCM 16-bit mono 16kHz аудио (5-10 сек).
     * @return Результат распознавания или null если не удалось.
     */
    suspend fun identify(pcmAudio: ByteArray): MusicRecognitionResult? = withContext(Dispatchers.IO) {
        val accessKey = prefs.acrCloudAccessKey.first()
        val accessSecret = prefs.acrCloudAccessSecret.first()
        val host = prefs.acrCloudHost.first()

        if (accessKey.isBlank() || accessSecret.isBlank() || host.isBlank()) {
            Log.w(TAG, "ACRCloud credentials not configured")
            return@withContext null
        }

        val audioData = if (pcmAudio.size > MAX_AUDIO_BYTES) {
            pcmAudio.copyOf(MAX_AUDIO_BYTES)
        } else {
            pcmAudio
        }

        try {
            val timestamp = (System.currentTimeMillis() / 1000).toString()
            val signature = generateSignature(
                accessKey = accessKey,
                accessSecret = accessSecret,
                dataType = DATA_TYPE_AUDIO,
                signatureVersion = SIGNATURE_VERSION,
                timestamp = timestamp
            )

            if (signature == null) {
                Log.e(TAG, "Failed to generate HMAC signature")
                return@withContext null
            }

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("access_key", accessKey)
                .addFormDataPart("data_type", DATA_TYPE_AUDIO)
                .addFormDataPart("signature_version", SIGNATURE_VERSION)
                .addFormDataPart("signature", signature)
                .addFormDataPart("sample_bytes", audioData.size.toString())
                .addFormDataPart("timestamp", timestamp)
                .addFormDataPart(
                    "sample",
                    "audio.pcm",
                    audioData.toRequestBody("application/octet-stream".toMediaTypeOrNull())
                )
                .build()

            val url = "https://$host$API_PATH"
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.use { resp ->
                val body = resp.body?.string()

                if (!resp.isSuccessful || body.isNullOrBlank()) {
                    Log.e(TAG, "ACRCloud API error: ${resp.code}")
                    return@withContext null
                }

                parseResponse(body)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACRCloud identify failed", e)
            null
        }
    }

    /**
     * Проверяет, настроены ли ACRCloud credentials.
     */
    suspend fun isConfigured(): Boolean {
        val key = prefs.acrCloudAccessKey.first()
        val secret = prefs.acrCloudAccessSecret.first()
        val host = prefs.acrCloudHost.first()
        return key.isNotBlank() && secret.isNotBlank() && host.isNotBlank()
    }

    /**
     * Генерирует HMAC-SHA1 подпись для ACRCloud API.
     */
    internal fun generateSignature(
        accessKey: String,
        accessSecret: String,
        dataType: String,
        signatureVersion: String,
        timestamp: String
    ): String? {
        val stringToSign = "POST\n$API_PATH\n$accessKey\n$dataType\n$signatureVersion\n$timestamp"
        return try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(accessSecret.toByteArray(), "HmacSHA1"))
            val rawHmac = mac.doFinal(stringToSign.toByteArray())
            Base64.getEncoder().encodeToString(rawHmac)
        } catch (e: InvalidKeyException) {
            Log.e(TAG, "Invalid ACRCloud secret key", e)
            null
        }
    }

    /**
     * Парсит JSON ответ ACRCloud API.
     */
    internal fun parseResponse(json: String): MusicRecognitionResult? {
        return try {
            val root = JSONObject(json)
            val status = root.getJSONObject("status")
            val code = status.getInt("code")

            if (code != 0) {
                Log.d(TAG, "ACRCloud: no match (code=$code, msg=${status.optString("msg")})")
                return null
            }

            val metadata = root.getJSONObject("metadata")
            val music = metadata.getJSONArray("music")
            if (music.length() == 0) return null

            val track = music.getJSONObject(0)
            val title = track.optString("title", "Unknown")
            val score = track.optInt("score", 0)

            val artists = track.optJSONArray("artists")
            val artist = if (artists != null && artists.length() > 0) {
                artists.getJSONObject(0).optString("name", "Unknown")
            } else "Unknown"

            val album = track.optJSONObject("album")?.optString("name")
            val releaseDate = track.optString("release_date", null)

            val durationMs = track.optLong("duration_ms", 0).let {
                if (it > 0) it else null
            }

            val genres = mutableListOf<String>()
            val genresArray = track.optJSONArray("genres")
            if (genresArray != null) {
                for (i in 0 until genresArray.length()) {
                    genresArray.getJSONObject(i).optString("name")?.let { genres.add(it) }
                }
            }

            MusicRecognitionResult(
                title = title,
                artist = artist,
                album = album,
                releaseDate = releaseDate,
                durationMs = durationMs,
                genres = genres,
                score = score
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ACRCloud response", e)
            null
        }
    }
}
