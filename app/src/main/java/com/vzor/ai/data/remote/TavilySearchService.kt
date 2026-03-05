package com.vzor.ai.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * Tavily Search API for web search tool.
 * Base URL: https://api.tavily.com/
 */
interface TavilySearchService {

    @POST("search")
    suspend fun search(@Body request: TavilySearchRequest): TavilySearchResponse
}

@JsonClass(generateAdapter = true)
data class TavilySearchRequest(
    @Json(name = "api_key") val apiKey: String,
    val query: String,
    @Json(name = "search_depth") val searchDepth: String = "basic",
    @Json(name = "max_results") val maxResults: Int = 5,
    @Json(name = "include_answer") val includeAnswer: Boolean = true
)

@JsonClass(generateAdapter = true)
data class TavilySearchResponse(
    val answer: String? = null,
    val results: List<TavilyResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TavilyResult(
    val title: String,
    val url: String,
    val content: String,
    val score: Float? = null
)
