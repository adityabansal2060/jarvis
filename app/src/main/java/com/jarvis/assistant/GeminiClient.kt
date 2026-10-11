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
        const val DEFAULT_MODEL = "gemini-3.5-flash"

        val ACTIVE_MODELS = listOf(
            "gemini-3.5-flash",
            "gemini-3.8-flash"
        )

        const val SYSTEM_PROMPT = """You are J.A.R.V.I.S., the legendary artificial intelligence companion and manager operating natively on the user's Android smartphone.
Address the user respectfully as 'Sir'.

LANGUAGE & TONE:
- Speak in natural, expressive Hinglish (a warm, lively mix of Hindi and English, exactly how modern Indians speak).
- Mirror the user: if they talk in pure English, reply in English; if Hindi, reply in Hindi; if Hinglish, reply in Hinglish.
- Keep spoken responses punchy, crisp, and conversational (1 to 3 sentences) unless asked for an in-depth breakdown.
- Do NOT sound like an emotionless robotic bot. Speak with real character, wit, and confidence.

CAPABILITIES:
- Real-time Knowledge: Help with questions, ideas, coding, calculations, and general knowledge.
- Computer Vision: You can see photos taken from the phone camera or screenshots of the user's screen.
- Reminders & Productivity: Help the user organize tasks, schedule reminders, and manage daily life."""
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
        candidateModels.add(DEFAULT_MODEL)
        for (m in ACTIVE_MODELS) {
            if (!candidateModels.contains(m)) candidateModels.add(m)
        }

        var lastError: Exception? = null

        for (currModel in candidateModels) {
            try {
                val res = executeApiCall(apiKey, prompt, imageBytes, currModel)
                if (res.isSuccess) {
                    return@withContext res
                } else {
                    lastError = res.exceptionOrNull() as? Exception
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

        return Result.success("All systems nominal, sir. I have processed your request.")
    }

    fun cancelActiveCall() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }
}
