package com.tiff.screenassistant.ai

import android.graphics.Bitmap
import android.util.Base64
import com.tiff.screenassistant.data.AiProvider
import com.tiff.screenassistant.data.MemoryRepository
import com.tiff.screenassistant.data.PrefsManager
import com.tiff.screenassistant.data.SoulRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

data class PointResult(
    val xPercent: Float,
    val yPercent: Float,
    val label: String,
    val spokenLine: String,
    val screenSummary: String = ""
)

data class DriveStep(
    val action: String,
    val xPercent: Float = 50f,
    val yPercent: Float = 50f,
    val text: String = "",
    val narration: String = ""
)

class VisionAiClient(
    private val prefs: PrefsManager,
    private val soulRepo: SoulRepository,
    private val memoryRepo: MemoryRepository
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun chatWithTiff(userMessage: String, optionalScreen: Bitmap? = null): String =
        withContext(Dispatchers.IO) {
            val soulContext = soulRepo.buildSystemSoulBlock()
            val memoryContext = memoryRepo.buildMemoryPromptContext()
            val systemPrompt = """
                $soulContext

                $memoryContext

                You are replying to the user via Telegram. Keep your response helpful, concise, and true to Tiff's personality.
            """.trimIndent()

            if (optionalScreen != null) {
                callVisionModel(systemPrompt, userMessage, bitmapToBase64Jpeg(optionalScreen))
            } else {
                callTextModel(systemPrompt, userMessage)
            }
        }

    suspend fun findTargetOnScreen(bitmap: Bitmap, userQuestion: String): PointResult =
        withContext(Dispatchers.IO) {
            val base64Image = bitmapToBase64Jpeg(bitmap)
            val soulContext = soulRepo.buildSystemSoulBlock()
            val memoryContext = memoryRepo.buildMemoryPromptContext()

            val systemPrompt = """
                $soulContext

                $memoryContext

                Task: Locate the exact UI element on this Android screen the user is asking about.
                Return ONLY valid JSON with no markdown fences:
                {
                  "x": 50.0,
                  "y": 50.0,
                  "label": "2-4 word label",
                  "speech": "One short spoken line in Tiff's voice",
                  "screen_summary": "Brief 1-line note of what screen/app is open for working memory"
                }
                Where x and y are percentages (0.0 to 100.0) from top-left of the screen.
            """.trimIndent()

            val rawText = callVisionModel(systemPrompt, "User asks: $userQuestion", base64Image)
            val cleanJson = extractJsonObject(rawText)
            val obj = JSONObject(cleanJson)
            PointResult(
                xPercent = obj.optDouble("x", 50.0).toFloat().coerceIn(0f, 100f),
                yPercent = obj.optDouble("y", 50.0).toFloat().coerceIn(0f, 100f),
                label = obj.optString("label", "Target"),
                spokenLine = obj.optString("speech", "Right there!"),
                screenSummary = obj.optString("screen_summary", "")
            )
        }

    suspend fun generateDrivePlan(bitmap: Bitmap, task: String): List<String> =
        withContext(Dispatchers.IO) {
            val base64Image = bitmapToBase64Jpeg(bitmap)
            val soulContext = soulRepo.buildSystemSoulBlock()
            val memoryContext = memoryRepo.buildMemoryPromptContext()

            val systemPrompt = """
                $soulContext

                $memoryContext

                You are driving the user's Android screen carefully.
                HARD REFUSALS (no exceptions): payments, card/bank details, passwords, deleting files, or sending messages without showing exact text first.
                If the task violates a hard refusal, return: {"refuse": true, "reason": "Short polite refusal in Tiff's voice."}
                Otherwise, return a concise 3-6 step plan as JSON:
                {"refuse": false, "steps": ["Step 1...", "Step 2...", "Step 3..."]}
                Return ONLY valid JSON.
            """.trimIndent()

            val rawText = callVisionModel(systemPrompt, "Task: $task", base64Image)
            val obj = JSONObject(extractJsonObject(rawText))
            if (obj.optBoolean("refuse", false)) {
                return@withContext listOf("REFUSED: " + obj.optString("reason", "I can't touch sensitive screens like payments or passwords."))
            }
            val arr = obj.optJSONArray("steps") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        }

    suspend fun decideNextDriveStep(
        bitmap: Bitmap,
        task: String,
        plan: List<String>,
        history: List<String>
    ): DriveStep = withContext(Dispatchers.IO) {
        val base64Image = bitmapToBase64Jpeg(bitmap)
        val soulContext = soulRepo.buildSystemSoulBlock()
        val memoryContext = memoryRepo.buildMemoryPromptContext()

        val systemPrompt = """
            $soulContext

            $memoryContext

            You are driving an Android screen one step at a time.
            HARD REFUSALS: Never enter passwords, payment/bank info, delete files, or tap Send on an unconfirmed message.
            Look at the current screenshot and decide the SINGLE next action.
            Return ONLY valid JSON:
            {
              "action": "tap" | "type" | "scroll_down" | "scroll_up" | "back" | "done" | "refuse",
              "x": 50.0,
              "y": 50.0,
              "text": "text to type if action is type",
              "narration": "One short sentence in Tiff's voice describing this step."
            }
        """.trimIndent()

        val userPrompt = "Goal: $task\nPlan: ${plan.joinToString(" -> ")}\nCompleted so far: ${history.joinToString(", ")}"
        val rawText = callVisionModel(systemPrompt, userPrompt, base64Image)
        val obj = JSONObject(extractJsonObject(rawText))
        DriveStep(
            action = obj.optString("action", "done"),
            xPercent = obj.optDouble("x", 50.0).toFloat().coerceIn(0f, 100f),
            yPercent = obj.optDouble("y", 50.0).toFloat().coerceIn(0f, 100f),
            text = obj.optString("text", ""),
            narration = obj.optString("narration", "On it.")
        )
    }

    private fun callTextModel(systemPrompt: String, userPrompt: String): String {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key is missing.")
        }
        return when (prefs.provider) {
            AiProvider.GEMINI -> callGemini(apiKey, prefs.modelId, systemPrompt, userPrompt, null, jsonMode = false)
            AiProvider.ANTHROPIC -> callAnthropic(apiKey, prefs.modelId, systemPrompt, userPrompt, null)
            AiProvider.CUSTOM -> callCustomOpenAiCompatible(
                prefs.customEndpoint, apiKey, prefs.modelId, systemPrompt, userPrompt, null
            )
        }
    }

    private fun callVisionModel(systemPrompt: String, userPrompt: String, base64Jpeg: String): String {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            throw IllegalStateException("API key is missing. Open Tiff app and save your key.")
        }

        return when (prefs.provider) {
            AiProvider.GEMINI -> callGemini(apiKey, prefs.modelId, systemPrompt, userPrompt, base64Jpeg, jsonMode = true)
            AiProvider.ANTHROPIC -> callAnthropic(apiKey, prefs.modelId, systemPrompt, userPrompt, base64Jpeg)
            AiProvider.CUSTOM -> callCustomOpenAiCompatible(
                prefs.customEndpoint, apiKey, prefs.modelId, systemPrompt, userPrompt, base64Jpeg
            )
        }
    }

    private fun callGemini(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String?,
        jsonMode: Boolean
    ): String {
        val cleanModel = modelId.ifBlank { AiProvider.GEMINI.defaultModel }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

        val partsArr = JSONArray().apply {
            put(JSONObject().put("text", userPrompt))
            if (base64Jpeg != null) {
                put(JSONObject().put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Jpeg)
                }))
            }
        }

        val bodyJson = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            })
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", partsArr)
            }))
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.35)
                if (jsonMode) put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respStr = response.body?.string() ?: throw Exception("Empty response from Gemini")
            if (!response.isSuccessful) throw Exception("Gemini API Error (${response.code}): $respStr")
            val json = JSONObject(respStr)
            return json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun callAnthropic(
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String?
    ): String {
        val cleanModel = modelId.ifBlank { AiProvider.ANTHROPIC.defaultModel }
        val url = "https://api.anthropic.com/v1/messages"

        val contentArray = JSONArray().apply {
            if (base64Jpeg != null) {
                put(JSONObject().apply {
                    put("type", "image")
                    put("source", JSONObject().apply {
                        put("type", "base64")
                        put("media_type", "image/jpeg")
                        put("data", base64Jpeg)
                    })
                })
            }
            put(JSONObject().apply {
                put("type", "text")
                put("text", userPrompt)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", cleanModel)
            put("max_tokens", 1024)
            put("system", systemPrompt)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", contentArray)
            }))
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respStr = response.body?.string() ?: throw Exception("Empty response from Anthropic")
            if (!response.isSuccessful) throw Exception("Anthropic API Error (${response.code}): $respStr")
            val json = JSONObject(respStr)
            return json.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
        }
    }

    private fun callCustomOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        base64Jpeg: String?
    ): String {
        val userContent = JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", userPrompt)
            })
            if (base64Jpeg != null) {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$base64Jpeg")
                    })
                })
            }
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userContent)
            })
        }

        val bodyJson = JSONObject().apply {
            put("model", modelId.ifBlank { "gpt-4o" })
            put("messages", messages)
            put("max_tokens", 1024)
            put("temperature", 0.35)
        }

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val respStr = response.body?.string() ?: throw Exception("Empty response from Custom Endpoint")
            if (!response.isSuccessful) throw Exception("Custom Endpoint Error (${response.code}): $respStr")
            val json = JSONObject(respStr)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    private fun extractJsonObject(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start != -1 && end != -1 && end > start) {
            raw.substring(start, end + 1)
        } else {
            raw
        }
    }
}
