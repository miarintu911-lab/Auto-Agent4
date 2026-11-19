package com.example.ai

import android.graphics.Bitmap
import android.util.Base64
import com.example.agent.AiProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

enum class RestSchemaKind {
    ACTION,
    RESEARCH
}

object RestAiClients {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun callProvider(
        provider: AiProvider,
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        promptText: String,
        screenshot: Bitmap?,
        schemaKind: RestSchemaKind = RestSchemaKind.ACTION
    ): String {
        return when (provider) {
            AiProvider.GOOGLE_AI_STUDIO -> callGoogleAiStudio(
                apiKey = apiKey,
                modelName = modelName,
                systemInstruction = systemInstruction,
                promptText = promptText,
                screenshot = screenshot,
                schemaKind = schemaKind
            )
            AiProvider.GROQ_CLOUD -> callOpenAiCompatibleWithFallback(
                endpoint = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = apiKey,
                modelName = modelName,
                systemInstruction = systemInstruction,
                promptText = promptText,
                screenshot = screenshot,
                extraHeaders = emptyMap(),
                providerLabel = "Groq Cloud",
                schemaKind = schemaKind
            )
            AiProvider.OPENROUTER -> callOpenAiCompatibleWithFallback(
                endpoint = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = apiKey,
                modelName = modelName,
                systemInstruction = systemInstruction,
                promptText = promptText,
                screenshot = screenshot,
                extraHeaders = mapOf(
                    "HTTP-Referer" to "https://github.com/tappilot-agent",
                    "X-Title" to "TapPilot Agent"
                ),
                providerLabel = "OpenRouter",
                schemaKind = schemaKind
            )
            AiProvider.FIREBASE_AI -> throw IllegalStateException("FIREBASE_AI uses Firebase SDK directly.")
        }
    }

    private fun callGoogleAiStudio(
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        promptText: String,
        screenshot: Bitmap?,
        schemaKind: RestSchemaKind
    ): String {
        val cleanModel = modelName.removePrefix("models/").trim()
        val encodedModel = URLEncoder.encode(cleanModel, "UTF-8")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$encodedModel:generateContent"

        val parts = JSONArray().apply {
            if (screenshot != null) {
                put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "image/jpeg")
                            .put("data", encodeBitmapToJpegBase64(screenshot))
                    )
                )
            }
            put(JSONObject().put("text", promptText))
        }

        val responseSchema = when (schemaKind) {
            RestSchemaKind.ACTION -> buildGoogleRestActionSchema()
            RestSchemaKind.RESEARCH -> buildGoogleRestResearchSchema()
        }

        val bodyJson = JSONObject().apply {
            put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
            )
            put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", parts)
                )
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.2)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", responseSchema)
            )
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        val responseText = executeHttp(request, "Google AI Studio")
        val root = JSONObject(responseText)
        val candidates = root.optJSONArray("candidates")
            ?: throw IOException("Google AI Studio returned no candidates.")
        if (candidates.length() == 0) {
            throw IOException("Google AI Studio returned empty candidates.")
        }
        val contentObj = candidates.getJSONObject(0).optJSONObject("content")
            ?: throw IOException("Google AI Studio response missing content.")
        val respParts = contentObj.optJSONArray("parts")
            ?: throw IOException("Google AI Studio response missing parts.")
        val rawText = (0 until respParts.length())
            .mapNotNull { respParts.optJSONObject(it)?.optString("text") }
            .joinToString("")
            .trim()
        return extractJsonObjectString(rawText)
    }

    private fun callOpenAiCompatibleWithFallback(
        endpoint: String,
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        promptText: String,
        screenshot: Bitmap?,
        extraHeaders: Map<String, String>,
        providerLabel: String,
        schemaKind: RestSchemaKind
    ): String {
        return try {
            callOpenAiCompatible(
                endpoint = endpoint,
                apiKey = apiKey,
                modelName = modelName,
                systemInstruction = systemInstruction,
                promptText = promptText,
                screenshot = screenshot,
                useJsonMode = true,
                extraHeaders = extraHeaders,
                providerLabel = providerLabel,
                schemaKind = schemaKind
            )
        } catch (e: HttpStatusException) {
            // Retry gracefully if a free model rejects response_format or multimodal image_url
            if (e.code in listOf(400, 422)) {
                try {
                    callOpenAiCompatible(
                        endpoint = endpoint,
                        apiKey = apiKey,
                        modelName = modelName,
                        systemInstruction = systemInstruction,
                        promptText = promptText,
                        screenshot = screenshot,
                        useJsonMode = false,
                        extraHeaders = extraHeaders,
                        providerLabel = providerLabel,
                        schemaKind = schemaKind
                    )
                } catch (e2: HttpStatusException) {
                    if (screenshot != null && e2.code in listOf(400, 404, 422)) {
                        callOpenAiCompatible(
                            endpoint = endpoint,
                            apiKey = apiKey,
                            modelName = modelName,
                            systemInstruction = systemInstruction,
                            promptText = promptText,
                            screenshot = null,
                            useJsonMode = false,
                            extraHeaders = extraHeaders,
                            providerLabel = providerLabel,
                            schemaKind = schemaKind
                        )
                    } else {
                        throw e2
                    }
                }
            } else if (screenshot != null && e.code == 404) {
                callOpenAiCompatible(
                    endpoint = endpoint,
                    apiKey = apiKey,
                    modelName = modelName,
                    systemInstruction = systemInstruction,
                    promptText = promptText,
                    screenshot = null,
                    useJsonMode = true,
                    extraHeaders = extraHeaders,
                    providerLabel = providerLabel,
                    schemaKind = schemaKind
                )
            } else {
                throw e
            }
        }
    }

    private fun callOpenAiCompatible(
        endpoint: String,
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        promptText: String,
        screenshot: Bitmap?,
        useJsonMode: Boolean,
        extraHeaders: Map<String, String>,
        providerLabel: String,
        schemaKind: RestSchemaKind
    ): String {
        val contractBody = when (schemaKind) {
            RestSchemaKind.ACTION -> """
                {
                  "actionType": "TAP" | "DRAG" | "LONG_PRESS" | "INPUT_TEXT" | "SCROLL_DOWN" | "SCROLL_UP" | "BACK" | "HOME" | "WAIT" | "COMPLETE" | "FAIL",
                  "targetNodeId": integer or null,
                  "x": integer or null,
                  "y": integer or null,
                  "endX": integer or null,
                  "endY": integer or null,
                  "durationMs": integer or null,
                  "textToType": string or null,
                  "waitSeconds": integer or null,
                  "memoryNote": string or null,
                  "learnedRule": string or null,
                  "foulOrMistakeLesson": string or null,
                  "reason": string,
                  "requiresConfirmation": boolean
                }
            """.trimIndent()
            RestSchemaKind.RESEARCH -> """
                {
                  "topicKey": string,
                  "topicTitle": string,
                  "rulesSummary": string,
                  "winningStrategies": [string],
                  "foulLessons": [string]
                }
            """.trimIndent()
        }

        val jsonContractInstruction = """
            $systemInstruction

            CRITICAL OUTPUT FORMAT:
            Respond with ONLY a single valid JSON object (no markdown fences) matching this exact structure:
            $contractBody
        """.trimIndent()

        val userMessage = JSONObject().put("role", "user")
        if (screenshot != null) {
            val base64 = encodeBitmapToJpegBase64(screenshot)
            val contentArray = JSONArray()
                .put(JSONObject().put("type", "text").put("text", promptText))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$base64"))
                )
            userMessage.put("content", contentArray)
        } else {
            userMessage.put("content", promptText)
        }

        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", jsonContractInstruction))
            .put(userMessage)

        val bodyJson = JSONObject().apply {
            put("model", modelName)
            put("temperature", 0.2)
            put("messages", messages)
            if (useJsonMode) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
        }

        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(bodyJson.toString().toRequestBody(jsonMediaType))
        extraHeaders.forEach { (k, v) -> builder.addHeader(k, v) }

        val responseText = executeHttp(builder.build(), providerLabel)
        val root = JSONObject(responseText)
        val choices = root.optJSONArray("choices")
            ?: throw IOException("$providerLabel returned no choices.")
        if (choices.length() == 0) {
            throw IOException("$providerLabel returned empty choices.")
        }
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        if (content.isBlank()) {
            throw IOException("$providerLabel returned empty message content.")
        }
        return extractJsonObjectString(content)
    }

    private fun executeHttp(request: Request, providerLabel: String): String {
        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val parsedErr = runCatching {
                    val obj = JSONObject(rawBody)
                    obj.optJSONObject("error")?.optString("message")
                        ?.takeIf { it.isNotBlank() }
                        ?: obj.optString("message").takeIf { it.isNotBlank() }
                }.getOrNull()
                val detail = parsedErr ?: rawBody.take(220)
                throw HttpStatusException(
                    code = response.code,
                    message = "$providerLabel HTTP ${response.code}: $detail"
                )
            }
            return rawBody
        }
    }

    private fun encodeBitmapToJpegBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 72, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    internal fun extractJsonObjectString(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0..end) {
            return raw.substring(start, end + 1)
        }
        return raw.trim()
    }

    private fun buildGoogleRestActionSchema(): JSONObject {
        val actionEnum = JSONArray(
            listOf(
                "TAP",
                "DRAG",
                "LONG_PRESS",
                "INPUT_TEXT",
                "SCROLL_DOWN",
                "SCROLL_UP",
                "BACK",
                "HOME",
                "WAIT",
                "COMPLETE",
                "FAIL"
            )
        )
        val props = JSONObject()
            .put("actionType", JSONObject().put("type", "STRING").put("enum", actionEnum))
            .put("targetNodeId", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("x", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("y", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("endX", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("endY", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("durationMs", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("textToType", JSONObject().put("type", "STRING").put("nullable", true))
            .put("waitSeconds", JSONObject().put("type", "INTEGER").put("nullable", true))
            .put("memoryNote", JSONObject().put("type", "STRING").put("nullable", true))
            .put("learnedRule", JSONObject().put("type", "STRING").put("nullable", true))
            .put("foulOrMistakeLesson", JSONObject().put("type", "STRING").put("nullable", true))
            .put("reason", JSONObject().put("type", "STRING"))
            .put("requiresConfirmation", JSONObject().put("type", "BOOLEAN"))

        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", props)
            .put("required", JSONArray(listOf("actionType", "reason", "requiresConfirmation")))
    }

    private fun buildGoogleRestResearchSchema(): JSONObject {
        val stringArray = JSONObject()
            .put("type", "ARRAY")
            .put("items", JSONObject().put("type", "STRING"))
        val props = JSONObject()
            .put("topicKey", JSONObject().put("type", "STRING"))
            .put("topicTitle", JSONObject().put("type", "STRING"))
            .put("rulesSummary", JSONObject().put("type", "STRING"))
            .put("winningStrategies", stringArray)
            .put("foulLessons", stringArray)

        return JSONObject()
            .put("type", "OBJECT")
            .put("properties", props)
            .put("required", JSONArray(listOf("topicKey", "topicTitle", "rulesSummary", "winningStrategies", "foulLessons")))
    }

    private class HttpStatusException(val code: Int, message: String) : IOException(message)
}
