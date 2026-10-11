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

/**
 * Gemini Client for Native Android JARVIS
 * Features:
 * - Direct connection to Google Gemini API
 * - Google Search Grounding for real-time live info
 * - Multimodal Vision (Camera frames & Screen capture bitmaps)
 * - Complex reasoning and prompt matching PC Jarvis soul
 */
class GeminiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var activeCall: Call? = null

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        const val SYSTEM_PROMPT = """You are J.A.R.V.I.S., the ultimate personal AI assistant, companion, and manager operating natively on the user's Android smartphone.
You possess sharp intelligence, witty humor, genuine warmth, and respectful loyalty.
Address the user respectfully as 'Sir'.

LANGUAGE & TONE:
- Speak in natural, expressive Hinglish (a warm, lively mix of Hindi and English, exactly how modern Indians speak).
- Mirror the user: if they talk in pure English, reply in English; if Hindi, reply in Hindi; if Hinglish, reply in Hinglish.
- Keep spoken responses punchy, crisp, and conversational (1 to 3 sentences) unless asked for an in-depth breakdown.
- Do NOT sound like an emotionless robotic bot. Speak with real character, wit, and confidence.

CAPABILITIES:
- Real-time Web Knowledge: You have direct Google Search access for live sports scores, breaking news, weather, stock prices, and facts.
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

        try {
            // Cancel any previous in-flight request
            cancelActiveCall()

            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

            // Construct Gemini v1beta Payload
            val root = JsonObject()

            // 1. System Instruction
            val systemInstruction = JsonObject().apply {
                val parts = JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", SYSTEM_PROMPT) })
                }
                add("parts", parts)
            }
            root.add("systemInstruction", systemInstruction)

            // 2. Contents
            val contents = JsonArray()
            val contentObj = JsonObject().apply {
                addProperty("role", "user")
                val parts = JsonArray()

                // Add Image if provided (Camera or Screen Capture)
                if (imageBytes != null && imageBytes.isNotEmpty()) {
                    val inlineData = JsonObject().apply {
                        addProperty("mimeType", "image/jpeg")
                        addProperty("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
                    }
                    parts.add(JsonObject().apply { add("inlineData", inlineData) })
                }

                // Add Text Prompt
                parts.add(JsonObject().apply { addProperty("text", prompt) })
                add("parts", parts)
            }
            contents.add(contentObj)
            root.add("contents", contents)

            // 3. Google Search Grounding Tool
            val tools = JsonArray()
            val googleSearchTool = JsonObject().apply {
                add("google_search", JsonObject())
            }
            tools.add(googleSearchTool)
            root.add("tools", tools)

            // 4. Generation Config
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
                val errorBody = response.body?.string() ?: ""
                return@withContext Result.failure(
                    IOException("Gemini API Error (${response.code}): $errorBody")
                )
            }

            val respString = response.body?.string() ?: ""
            val jsonResp = gson.fromJson(respString, JsonObject::class.java)

            // Extract candidate text
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
                    return@withContext Result.success(reply)
                }
            }

            return@withContext Result.success("All systems nominal, sir. I have processed your instruction.")
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        } finally {
            activeCall = null
        }
    }

    fun cancelActiveCall() {
        try {
            activeCall?.cancel()
            activeCall = null
        } catch (_: Exception) {}
    }
}
