package com.kevinluo.autoglm.planner.deepseek

import com.kevinluo.autoglm.util.Logger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DeepSeekPlannerClient(
    private val okHttpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val baseUrlProvider: () -> String = { "https://api.deepseek.com" },
    private val modelProvider: () -> String = { "deepseek-chat" },
) {
    private val currentCall = AtomicReference<okhttp3.Call?>(null)

    fun cancelCurrentRequest() {
        currentCall.getAndSet(null)?.cancel()
    }

    /**
     * Sends messages to DeepSeek and returns the assistant message.
     * This method is tool-calling aware: it returns tool_calls when present.
     */
    @Throws(IOException::class)
    suspend fun chat(
        messages: List<DsMessage>,
        tools: List<DsTool>,
        temperature: Double = 0.2,
    ): DsMessage = withContext(Dispatchers.IO) {
        val url = baseUrlProvider().trimEnd('/') + "/chat/completions"
        val apiKey = apiKeyProvider()

        val reqJson =
            JSONObject().apply {
                put("model", modelProvider())
                put("temperature", temperature)
                put("messages", JSONArray(messages.map { it.toJson() }))
                put("tools", JSONArray(tools.map { it.toJson() }))
                put("tool_choice", "auto")
            }

        val body = reqJson.toString().toRequestBody(JSON_MEDIA)

        val request =
            Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

        val call = okHttpClient.newCall(request)
        currentCall.set(call)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string()
                    throw IOException("DeepSeek API error: HTTP ${response.code}. body=$err")
                }

                val text = response.body?.string().orEmpty()
                val json = JSONObject(text)

                val choices = json.optJSONArray("choices") ?: JSONArray()
                if (choices.length() == 0) {
                    throw IOException("DeepSeek API returned no choices. body=$text")
                }

                val choice0 = choices.getJSONObject(0)
                val msgJson =
                    choice0.optJSONObject("message")
                        ?: throw IOException("DeepSeek API response missing message. body=$text")

                val dsMessage = msgJson.fromJsonToDsMessage()
                Logger.d(TAG, "DeepSeek message role=${dsMessage.role}, tool_calls=${dsMessage.tool_calls?.size ?: 0}")
                dsMessage
            }
        } finally {
            currentCall.compareAndSet(call, null)
        }
    }

    private fun DsMessage.toJson(): JSONObject =
        JSONObject().apply {
            put("role", role)
            if (content != null) put("content", content)
            if (tool_calls != null) {
                val arr =
                    JSONArray().apply {
                        tool_calls.forEach { put(it.toJson()) }
                    }
                put("tool_calls", arr)
            }
            if (tool_call_id != null) put("tool_call_id", tool_call_id)
        }

    private fun DsTool.toJson(): JSONObject =
        JSONObject().apply {
            put("type", type)
            put("function", function.toJson())
        }

    private fun DsFunctionDef.toJson(): JSONObject =
        JSONObject().apply {
            put("name", name)
            if (description != null) put("description", description)
            if (parameters != null) put("parameters", JSONObject(parameters))
        }

    private fun DsToolCall.toJson(): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("type", type)
            put("function", function.toJson())
        }

    private fun DsToolCallFunction.toJson(): JSONObject =
        JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }

    private fun JSONObject.fromJsonToDsMessage(): DsMessage {
        val role = optString("role")
        val content = if (has("content") && !isNull("content")) optString("content") else null

        val toolCallsArr = optJSONArray("tool_calls")
        val toolCalls =
            toolCallsArr?.let { arr ->
                buildList {
                    for (i in 0 until arr.length()) {
                        val tc = arr.getJSONObject(i)
                        val id = tc.optString("id")
                        val type = tc.optString("type")
                        val fn = tc.optJSONObject("function") ?: JSONObject()
                        add(
                            DsToolCall(
                                id = id,
                                type = type,
                                function =
                                    DsToolCallFunction(
                                        name = fn.optString("name"),
                                        arguments = fn.optString("arguments"),
                                    ),
                            ),
                        )
                    }
                }
            }

        val toolCallId =
            if (has("tool_call_id") && !isNull("tool_call_id")) optString("tool_call_id") else null

        return DsMessage(
            role = role,
            content = content,
            tool_calls = toolCalls,
            tool_call_id = toolCallId,
        )
    }

    companion object {
        private const val TAG = "DeepSeekPlannerClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}