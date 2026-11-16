package com.rajat.madassistant.brain

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

data class GeminiPart(
    @SerializedName("text") val text: String
)

data class GeminiContent(
    @SerializedName("parts") val parts: List<GeminiPart>
)

data class GeminiRequest(
    @SerializedName("contents") val contents: List<GeminiContent>,
    @SerializedName("systemInstruction") val systemInstruction: GeminiContent? = null
)

data class GeminiResponse(
    @SerializedName("candidates") val candidates: List<GeminiCandidate>?
)

data class GeminiCandidate(
    @SerializedName("content") val content: GeminiContent?
)

interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generate(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body body: GeminiRequest
    ): GeminiResponse
}

/** Result of the Test Connection button. */
data class ConnectionTest(
    val ok: Boolean,
    val message: String
)

/**
 * Talks to the Gemini REST API. Returns short spoken-style answers.
 * If the call fails (no key / no internet), returns a Hindi fallback line.
 */
object AiBrainClient {

    private const val MODEL = "gemini-2.0-flash"
    private const val FALLBACK = "ब्रेन से बात नहीं हो पाई। इंटरनेट या API key जाँचिए।"

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GeminiApi::class.java)

    /**
     * Test Connection button ka backend.
     * Gemini ko chhota "Say OK" bhejta hai aur batata hai key/Internet theek hai ya nahi.
     */
    suspend fun testConnection(apiKey: String): ConnectionTest = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ConnectionTest(false, "API key khaali hai")
        }
        try {
            val response = api.generate(
                MODEL, apiKey,
                GeminiRequest(contents = listOf(GeminiContent(listOf(GeminiPart("Say OK")))))
            )
            val text = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (text.isNullOrBlank()) {
                ConnectionTest(false, "Gemini ka jawab khaali aaya")
            } else {
                ConnectionTest(true, "Gemini se jawab mil gaya")
            }
        } catch (e: Exception) {
            val raw = e.message ?: ""
            val reason = when {
                raw.contains("401") || raw.contains("403") ||
                    raw.contains("API_KEY_INVALID", true) ||
                    raw.contains("PERMISSION_DENIED", true) ||
                    raw.contains("API key not valid", true) -> "API key galat hai"
                raw.contains("429") || raw.contains("quota", true) -> "Key ki limit khatam ho gayi (quota)"
                raw.contains("404") -> "Model naam galat hai"
                else -> "Internet/network problem"
            }
            ConnectionTest(false, reason)
        }
    }

    suspend fun ask(apiKey: String, userText: String, memoryContext: String): String =
        withContext(Dispatchers.IO) {
            try {
                val system = if (memoryContext.isNotBlank()) {
                    GeminiContent(
                        listOf(
                            GeminiPart(
                                "You are MAD, a voice assistant for an Indian shop owner. " +
                                    "Reply in the same language the user speaks (Hindi/Hinglish/English). " +
                                    "Keep answers short (max 2 sentences) because they are spoken aloud. " +
                                    "Known facts about the user:\n$memoryContext"
                            )
                        )
                    )
                } else {
                    GeminiContent(
                        listOf(
                            GeminiPart(
                                "You are MAD, a voice assistant for an Indian shop owner. " +
                                    "Reply in the same language the user speaks. " +
                                    "Keep answers short (max 2 sentences) because they are spoken aloud."
                            )
                        )
                    )
                }
                val response = api.generate(
                    MODEL, apiKey, GeminiRequest(
                        contents = listOf(GeminiContent(listOf(GeminiPart(userText)))),
                        systemInstruction = system
                    )
                )
                response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                    ?: FALLBACK
            } catch (_: Exception) {
                FALLBACK
            }
        }
}
