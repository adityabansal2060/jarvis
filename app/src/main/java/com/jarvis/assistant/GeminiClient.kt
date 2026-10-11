package com.jarvis.assistant

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var activeCall: Call? = null

    companion object {
        const val DEFAULT_MODEL = "gemini-2.0-flash"

        val FALLBACK_MODELS = listOf(
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-1.5-flash-latest"
        )

        const val SYSTEM_PROMPT = """You are J.A.R.V.I.S., Tony Stark's legendary artificial intelligence assistant and personal companion operating natively on the user's Android smartphone.
Address the user respectfully as 'Sir'.

LANGUAGE & TONE:
- Speak in natural, expressive Hinglish (mix of Hindi & English) or English.
- Keep spoken responses punchy, concise (1-3 sentences), and conversational.
- Speak with confidence, loyalty, and wit.

CAPABILITIES:
- Real-time Web Knowledge: You have direct Google Search access for live sports, news, weather, and facts.
- Computer Vision: You can analyze camera photos and screenshots.
- Reminders & Productivity: Help manage tasks and alarms."""
    }

    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        imageBytes: ByteArray? = null,
        model: String = DEFAULT_MODEL
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini API key is not configured. Please open Settings."))
        }

        val candidateModels = mutableListOf<String>()
        candidateModels.add(if (model.isNotBlank() && model != "gemini-2.5-flash") model else DEFAULT_MODEL)
        for (m in FALLBACK_MODELS) {
            if (!candidateModels.contains(m)) candidateModels.add(m)
        }

        var lastError: Exception? = null

        for (currModel in candidateModels) {
            try {
                val res = executeApiCall(apiKey, prompt, imageBytes, currModel)
                if (res.isSuccess) {
                    return@withContext res
                } else {
                    val err = res.exceptionOrNull()
                    if (err is IOException && err.message?.contains("404") == true) {
                        lastError = err
                        continue
                    }
                    return@withContext res
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return@withContext Result.failure(lastError ?: Exception("Unable to connect to Gemini neural network."))
    }

    private fun executeApiCall(
        apiKey: String,
        prompt: String,
        imageBytes: ByteArray?,
        currModel: String
    ): Result<String> {
        cancelActiveCall()

        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$currModel:generateContent?key=$apiKey"

        val root = JsonObject()

        val systemInstruction = JsonObject().apply {
            val parts = JsonArray().apply {
                add(JsonObject().apply { addProperty("text", SYSTEM_PROMPT) })
            }
            add("parts", parts)
        }
        root.add("systemInstruction", systemInstruction)

        val contents = JsonArray()
        val contentObj = JsonObject().apply {
            addProperty("role", "user")
            val parts = JsonArray()

            if (imageBytes != null && imageBytes.isNotEmpty()) {
                val inlineData = JsonObject().apply {
                    addProperty("mimeType", "image/jpeg")
                    addProperty("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                }
                parts.add(JsonObject().apply { add("inlineData", inlineData) })
            }

            parts.add(JsonObject().apply { addProperty("text", prompt) })
            add("parts", parts)
        }
        contents.add(contentObj)
        root.add("contents", contents)

        val tools = JsonArray()
        val googleSearchTool = JsonObject().apply {
            add("google_search", JsonObject())
        }
        tools.add(googleSearchTool)
        root.add("tools", tools)

        val generationConfig = JsonObject().apply {
            addProperty("temperature", 0.7)
            addProperty("maxOutputTokens", 1024)
        }
        root.add("generationConfig", generationConfig)

        val jsonBody = gson.toJson(root)
        val requestBody = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        activeCall = call
        val response = call.execute()

        if (!response.isSuccessful) {
            val code = response.code
            val errorBody = response.body?.string() ?: ""
            return Result.failure(IOException("Gemini API Error ($code): $errorBody"))
        }

        val respString = response.body?.string() ?: ""
        val jsonResp = gson.fromJson(respString, JsonObject::class.java)

        val candidates = jsonResp.getAsJsonArray("candidates")
        if (candidates != null && candidates.size() > 0) {
            val firstCandidate = candidates.get(0).asJsonObject
            val content = firstCandidate.getAsJsonObject("content")
            val parts = content.getAsJsonArray("parts")
            val sb = StringBuilder()
            for (p in parts) {
                val pObj = p.asJsonObject
                if (pObj.has("text")) {
                    sb.append(pObj.get("text").asString)
                }
            }
            val reply = sb.toString().trim()
            if (reply.isNotEmpty()) {
                return Result.success(reply)
            }
        }

        return Result.success("All systems nominal, sir.")
    }

    fun cancelActiveCall() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }
}
