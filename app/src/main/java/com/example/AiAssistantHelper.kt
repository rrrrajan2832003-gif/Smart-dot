package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Built-in Free AI Assistant helper:
 * - If user enters their own free Google Gemini API key in Settings ("gemini_api_key"),
 *   calls Gemini REST API directly via OkHttp for instant Ask AI & Screen Summary.
 * - If empty, opens Google Gemini or ChatGPT app / free web interface as fallback.
 */
object AiAssistantHelper {

    const val PREF_GEMINI_API_KEY = "gemini_api_key"
    private const val GEMINI_ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun getSavedApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        return prefs.getString(PREF_GEMINI_API_KEY, "")?.trim() ?: ""
    }

    fun collectVisibleScreenText(): String {
        val nodes = SmartDotAccessibilityService.instance?.extractVisibleScreenTextNodes().orEmpty()
        return nodes
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
    }

    fun buildScreenSummaryPrompt(screenText: String): String {
        val trimmed = screenText.take(6000)
        return "Summarize the key points and actionable information from this phone screen clearly and concisely:\n\n$trimmed"
    }

    fun buildGeminiRequestBody(prompt: String): String {
        val part = JSONObject().put("text", prompt)
        val partsArray = JSONArray().put(part)
        val contentObj = JSONObject().put("parts", partsArray)
        val contentsArray = JSONArray().put(contentObj)
        return JSONObject().put("contents", contentsArray).toString()
    }

    fun parseGeminiResponseText(responseJson: String): String? {
        return runCatching {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates") ?: return@runCatching null
            if (candidates.length() == 0) return@runCatching null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return@runCatching null
            val parts = content.optJSONArray("parts") ?: return@runCatching null
            val builder = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.getJSONObject(i).optString("text", "")
                if (partText.isNotEmpty()) {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append(partText)
                }
            }
            builder.toString().trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    suspend fun queryGemini(
        apiKey: String,
        prompt: String,
        endpointOverride: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key is empty"))
        }
        return@withContext runCatching {
            val baseUrl = endpointOverride ?: GEMINI_ENDPOINT
            val url = "$baseUrl?key=$apiKey"
            val bodyJson = buildGeminiRequestBody(prompt)
            val requestBody = bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errorMsg = runCatching {
                        JSONObject(raw).optJSONObject("error")?.optString("message")
                    }.getOrNull()
                    throw IllegalStateException(errorMsg ?: "HTTP ${response.code}")
                }
                parseGeminiResponseText(raw)
                    ?: throw IllegalStateException("Empty AI response")
            }
        }
    }

    fun buildExternalAiFallbackIntents(context: Context): List<Intent> {
        val pm = context.packageManager
        val candidates = ArrayList<Intent>()

        pm.getLaunchIntentForPackage("com.google.android.apps.bard")?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            candidates.add(intent)
        }
        pm.getLaunchIntentForPackage("com.openai.chatgpt")?.let { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            candidates.add(intent)
        }
        candidates.add(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        candidates.add(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return candidates
    }

    fun launchExternalAiFallback(context: Context): Boolean {
        for (intent in buildExternalAiFallbackIntents(context)) {
            val launched = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }
}
