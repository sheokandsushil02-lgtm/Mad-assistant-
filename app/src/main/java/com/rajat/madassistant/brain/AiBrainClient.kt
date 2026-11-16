package com.rajat.madassistant.brain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Result of the Test Connection button. */
data class ConnectionTest(
    val ok: Boolean,
    val message: String
)

/**
 * Multi-provider AI brain: Gemini / OpenAI / Claude / Grok.
 * Provider + model dono user Settings me chunta hai.
 * Custom model naam bhi type kar sakta hai (naye/future models ke liye).
 */
object AiBrainClient {

    private const val FALLBACK = "ब्रेन से बात नहीं हो पाई। इंटरनेट या API key जाँचिए।"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** AI companies — Settings dropdown me dikhti hain. */
    val PROVIDERS = listOf("Gemini", "OpenAI", "Claude", "Grok")

    /** Har company ke models. Custom option se koi bhi naam add ho sakta hai. */
    val MODELS: Map<String, List<String>> = mapOf(
        "Gemini" to listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-2.0-flash",
            "gemini-2.0-flash-lite",
            "gemini-flash-latest"
        ),
        "OpenAI" to listOf(
            "gpt-4o",
            "gpt-4o-mini",
            "gpt-4.1",
            "gpt-4.1-mini",
            "gpt-4.1-nano",
            "o4-mini"
        ),
        "Claude" to listOf(
            "claude-sonnet-4-5",
            "claude-haiku-4-5",
            "claude-3-7-sonnet-latest",
            "claude-3-5-haiku-latest"
        ),
        "Grok" to listOf(
            "grok-4",
            "grok-4-fast",
            "grok-3",
            "grok-3-mini"
        )
    )

    fun defaultProvider(): String = "Gemini"

    fun modelsFor(provider: String): List<String> =
        MODELS[provider] ?: MODELS.getValue("Gemini")

    fun defaultModel(provider: String): String = modelsFor(provider).first()

    private fun systemText(memoryContext: String): String =
        "You are MAD, a voice assistant for an Indian shop owner. " +
            "Reply in the same language the user speaks (Hindi/Hinglish/English). " +
            "Keep answers short (max 2 sentences) because they are spoken aloud." +
            if (memoryContext.isNotBlank()) " Known facts about the user:\n$memoryContext" else ""

    /**
     * Test Connection button ka backend — chuna hua provider+model asli call karke dekhta hai.
     */
    suspend fun testConnection(provider: String, apiKey: String, model: String): ConnectionTest =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext ConnectionTest(false, "API key khaali hai")
            }
            try {
                val reply = callApi(provider, apiKey, model, "Say OK", "")
                if (reply.isBlank()) {
                    ConnectionTest(false, "AI ka jawab khaali aaya")
                } else {
                    ConnectionTest(true, "Connection OK ($model)")
                }
            } catch (e: Exception) {
                ConnectionTest(false, classify(e))
            }
        }

    suspend fun ask(
        provider: String,
        apiKey: String,
        model: String,
        userText: String,
        memoryContext: String
    ): String = withContext(Dispatchers.IO) {
        try {
            callApi(provider, apiKey, model, userText, memoryContext).ifBlank { FALLBACK }
        } catch (_: Exception) {
            FALLBACK
        }
    }

    private fun classify(e: Exception): String {
        val raw = e.message ?: ""
        return when {
            raw.contains("401") || raw.contains("403") ||
                raw.contains("API_KEY_INVALID", true) ||
                raw.contains("invalid_api_key", true) -> "API key galat hai"
            raw.contains("429") -> "Key ki limit khatam ho gayi (quota)"
            raw.contains("404") || raw.contains("not_found", true) ->
                "Ye model available nahi — dusra model chuno"
            else -> "Internet/network problem"
        }
    }

    private fun callApi(
        provider: String,
        apiKey: String,
        model: String,
        userText: String,
        memoryContext: String
    ): String {
        val system = systemText(memoryContext)
        return when (provider) {
            "OpenAI" -> openAiStyle("https://api.openai.com/v1/chat/completions", apiKey, model, system, userText)
            "Grok" -> openAiStyle("https://api.x.ai/v1/chat/completions", apiKey, model, system, userText)
            "Claude" -> anthropicStyle(apiKey, model, system, userText)
            else -> geminiStyle(apiKey, model, system, userText)
        }
    }

    @Throws(Exception::class)
    private fun httpPost(url: String, bodyJson: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url).post(bodyJson.toRequestBody(JSON))
        headers.forEach { (k, v) -> builder.header(k, v) }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} $text")
            return text
        }
    }

    /** Gemini (generativelanguage API). */
    private fun geminiStyle(apiKey: String, model: String, system: String, userText: String): String {
        val userContent = JsonObject().apply {
            add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", userText) }) })
        }
        val systemContent = JsonObject().apply {
            add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", system) }) })
        }
        val body = JsonObject().apply {
            add("contents", JsonArray().apply { add(userContent) })
            add("systemInstruction", systemContent)
        }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val raw = httpPost(url, body.toString())
        return runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonArray("candidates").get(0).asJsonObject
                .getAsJsonObject("content")
                .getAsJsonArray("parts").get(0).asJsonObject
                .get("text").asString.trim()
        }.getOrDefault("")
    }

    /** OpenAI + Grok (dono ka format same hai). */
    private fun openAiStyle(baseUrl: String, apiKey: String, model: String, system: String, userText: String): String {
        val messages = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "system"); addProperty("content", system) })
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
        }
        val body = JsonObject().apply {
            addProperty("model", model)
            add("messages", messages)
            addProperty("max_tokens", 200)
        }
        val raw = httpPost(baseUrl, body.toString(), mapOf("Authorization" to "Bearer $apiKey"))
        return runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonArray("choices").get(0).asJsonObject
                .getAsJsonObject("message").get("content").asString.trim()
        }.getOrDefault("")
    }

    /** Claude (Anthropic messages API). */
    private fun anthropicStyle(apiKey: String, model: String, system: String, userText: String): String {
        val body = JsonObject().apply {
            addProperty("model", model)
            addProperty("max_tokens", 200)
            addProperty("system", system)
            add("messages", JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", userText) })
            })
        }
        val raw = httpPost(
            "https://api.anthropic.com/v1/messages",
            body.toString(),
            mapOf(
                "x-api-key" to apiKey,
                "anthropic-version" to "2023-06-01"
            )
        )
        return runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonArray("content").get(0).asJsonObject
                .get("text").asString.trim()
        }.getOrDefault("")
    }
}
