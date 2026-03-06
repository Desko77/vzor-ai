package com.vzor.ai.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

/**
 * Yandex Translate API v2.
 * Быстрый машинный перевод для translation pipeline.
 * Латентность ~100-200ms vs ~400-700ms через LLM.
 *
 * @see <a href="https://cloud.yandex.ru/docs/translate/api-ref/Translation/translate">API Reference</a>
 */
interface YandexTranslateService {

    @POST("translate/v2/translate")
    suspend fun translate(
        @Header("Authorization") auth: String,
        @Body request: YandexTranslateRequest
    ): YandexTranslateResponse

    @POST("translate/v2/detect")
    suspend fun detectLanguage(
        @Header("Authorization") auth: String,
        @Body request: YandexDetectRequest
    ): YandexDetectResponse
}

@JsonClass(generateAdapter = true)
data class YandexTranslateRequest(
    @Json(name = "sourceLanguageCode") val sourceLanguageCode: String? = null,
    @Json(name = "targetLanguageCode") val targetLanguageCode: String,
    val texts: List<String>,
    @Json(name = "folderId") val folderId: String? = null
)

@JsonClass(generateAdapter = true)
data class YandexTranslateResponse(
    val translations: List<YandexTranslation>
)

@JsonClass(generateAdapter = true)
data class YandexTranslation(
    val text: String,
    @Json(name = "detectedLanguageCode") val detectedLanguageCode: String? = null
)

@JsonClass(generateAdapter = true)
data class YandexDetectRequest(
    val text: String,
    @Json(name = "languageCodeHints") val languageCodeHints: List<String> = listOf("ru", "en")
)

@JsonClass(generateAdapter = true)
data class YandexDetectResponse(
    @Json(name = "languageCode") val languageCode: String
)
