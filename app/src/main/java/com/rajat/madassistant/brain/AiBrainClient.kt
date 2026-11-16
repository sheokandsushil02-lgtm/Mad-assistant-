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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Result of the Test Connection button. */
data class ConnectionTest(
    val ok: Boolean,
    val message: String
)

/** Result of Fetch Available Models (Gemini). */
data class ModelsFetch(
    val ok: Boolean,
    val models: List<String>,
    val message: String
)

/**
 * Multi-provider AI brain: Gemini / OpenAI / Claude / Grok.
 *
 * Gemini-specific fixes:
 *  - Model ID hamesha trim + lowercase (uppercase 404 deta hai).
 *  - Naye "AQ." wale AI Studio keys header "x-goog-api-key" se jaate hain (URL encoding issue nahi).
 *  - Timeouts 30/60/30 sec — thinking models der se jawab dete hain.
 *  - Asli HTTP error dikhta hai, "Internet problem" sirf net fail par.
 */
object AiBrainClient {

    private const val FALLBACK = "ब्रेन से बात नहीं हो पाई। इंटरनेट या API key जाँचिए।"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** AI companies — Settings dropdown me dikhti hain. */
    val PROVIDERS = listOf("Gemini", "OpenAI", "Claude", "Grok")

    /** Har company ke models (Gemini list updated — abhi active Google AI Studio models). */
    val MODELS: Map<String, List<String>> = mapOf(
        "Gemini" to listOf(
            "gemini-flash-latest",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro"
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

    /** BUGFIX #1: uppercase/jaada space wale model naam 404 dete hain. */
    fun normalizeModel(model: String): String = model.trim().lowercase()

    /** BUGFIX #2: key me extra space 400/403 de deta hai. */
    fun normalizeKey(key: String): String = key.trim()

    private fun systemText(memoryContext: String): String =
        "You are MAD, a voice assistant for an Indian shop owner. " +
            "Reply in the same language the user speaks (Hindi/Hinglish/English). " +
            "Keep answers short (max 2 sentences) because they are spoken aloud." +
            if (memoryContext.isNotBlank()) " Known facts about the user:\n$memoryContext" else ""

    /**
     * Test Connection — chuna hua provider+model asli call karke dekhta hai.
     * Errors ab asli wale dikhte hain (Internet problem sirf net fail par).
     */
    suspend fun testConnection(provider: String, apiKey: String, model: String): ConnectionTest =
        withContext(Dispatchers.IO) {
            val key = normalizeKey(apiKey)
            val modelId = normalizeModel(model)
            if (key.isBlank()) {
                return@withContext ConnectionTest(false, "API key khaali hai")
            }
            try {
                val reply = callApi(provider, key, modelId, "Say OK", "")
                if (reply.isBlank()) {
                    ConnectionTest(false, "AI ka jawab khaali aaya")
                } else {
                    ConnectionTest(true, "Connection OK ($modelId)")
                }
            } catch (e: Exception) {
                ConnectionTest(false, readableError(e))
            }
        }

    /**
     * BUGFIX #1b: Gemini ki asli available models ki list laata hai
     * (GET /v1beta/models) — jo models tumhari key sach me support karti hai.
     */
    suspend fun fetchGeminiModels(apiKey: String): ModelsFetch = withContext(Dispatchers.IO) {
        val key = normalizeKey(apiKey)
        if (key.isBlank()) {
            return@withContext ModelsFetch(false, emptyList(), "API key khaali hai")
        }
        try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
                .header("x-goog-api-key", key)
                .get()
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${extractApiError(text)}")
                }
                val models = mutableListOf<String>()
                runCatching {
                    val array = JsonParser.parseString(text).asJsonObject
                        .getAsJsonArray("models")
                    for (i in 0 until array.size()) {
                        val m = array.get(i).asJsonObject
                        val name = m.get("name")?.asString ?: continue
                        val methods = runCatching {
                            m.getAsJsonArray("supportedGenerationMethods")
                                .map { it.asString }
                        }.getOrDefault(emptyList())
                        if (methods.contains("generateContent") && name.startsWith("models/gemini")) {
                            models.add(name.removePrefix("models/"))
                        }
                    }
                }
                if (models.isEmpty()) {
                    ModelsFetch(false, emptyList(), "Koi generateContent model nahi mila is key par")
                } else {
                    ModelsFetch(true, models, "${models.size} models mile")
                }
            }
        } catch (e: Exception) {
            ModelsFetch(false, emptyList(), readableError(e))
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
            callApi(provider, normalizeKey(apiKey), normalizeModel(model), userText, memoryContext)
                .ifBlank { FALLBACK }
        } catch (_: Exception) {
            FALLBACK
        }
    }

    /**
     * BUGFIX #3: "Internet problem" ab sirf net fail par.
     * Timeout, HTTP 400/401/403/404 sab apna asli message dikhte hain.
     */
    private fun readableError(e: Exception): String {
        val raw = e.message ?: ""
        val httpMatch = Regex("HTTP (\\d+): ([\\s\\S]*)").find(raw)
        return when {
            e is UnknownHostException || e is ConnectException ->
                "Internet problem — net/Wi-Fi check karo"
            e is SocketTimeoutException ->
                "Request timed out — model ne jawab dene me der ki (dobara try karo)"
            httpMatch != null -> "Error ${httpMatch.groupValues[1]}: ${httpMatch.groupValues[2]}"
            else -> raw.ifBlank { "Internet/network problem" }
        }
    }

    /** Google/OpenAI/Anthropic error body se exact message nikalta hai. */
    private fun extractApiError(body: String): String {
        val apiMsg = runCatching {
            JsonParser.parseString(body).asJsonObject
                .getAsJsonObject("error").get("message").asString
        }.getOrNull()
        return apiMsg ?: body.take(200)
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
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${extractApiError(text)}")
            return text
        }
    }

    /**
     * Gemini — naye "AQ." keys header se jaate hain (x-goog-api-key),
     * model ID lowercase + trim karke URL me jaata hai.
     */
    private fun geminiStyle(apiKey: String, model: String, system: String, userText: String): String {
        val modelId = normalizeModel(model)
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val raw = httpPost(url, body.toString(), mapOf("x-goog-api-key" to normalizeKey(apiKey)))
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
            addProperty("model", normalizeModel(model))
            add("messages", messages)
            addProperty("max_tokens", 200)
        }
        val raw = httpPost(baseUrl, body.toString(), mapOf("Authorization" to "Bearer ${normalizeKey(apiKey)}"))
        return runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonArray("choices").get(0).asJsonObject
                .getAsJsonObject("message").get("content").asString.trim()
        }.getOrDefault("")
    }

    /** Claude (Anthropic messages API). */
    private fun anthropicStyle(apiKey: String, model: String, system: String, userText: String): String {
        val body = JsonObject().apply {
            addProperty("model", model.trim().lowercase())
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
                "x-api-key" to normalizeKey(apiKey),
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
