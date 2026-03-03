package com.kevinluo.autoglm.planner.tools

import com.kevinluo.autoglm.api.CapabilitiesProvider
import com.kevinluo.autoglm.model.ChatMessage
import com.kevinluo.autoglm.model.ModelClient
import com.kevinluo.autoglm.model.ModelResult
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.delay
import org.json.JSONObject

class PlannerToolDispatcher(
    private val screenshotService: ScreenshotService,
    private val vlmClient: ModelClient,
) {
    data class ToolResult(
        val ok: Boolean,
        val content: JSONObject,
    )

    suspend fun getScreenshot(reason: String? = null): ToolResult {
        Logger.d(TAG, "getScreenshot reason=$reason")
        val s = screenshotService.capture()
        val json =
            JSONObject()
                .put("ok", true)
                .put("width", s.width)
                .put("height", s.height)
                .put("originalWidth", s.originalWidth)
                .put("originalHeight", s.originalHeight)
                .put("isSensitive", s.isSensitive)
                .put("base64Data", s.base64Data)
        return ToolResult(true, json)
    }

    suspend fun listScripts(): ToolResult {
        val json = CapabilitiesProvider.autoJs().listWorkspaceScriptsJson()
        return ToolResult(json.optBoolean("ok", false), json)
    }

    suspend fun runScript(path: String, params: JSONObject?): ToolResult {
        val json = CapabilitiesProvider.autoJs().runScriptJson(path, params)
        return ToolResult(json.optBoolean("ok", false), json)
    }

    suspend fun watchScriptUntilFinished(
        executionId: Int,
        pollIntervalMs: Long = 1000L,
        timeoutMs: Long? = null,
    ): ToolResult {
        val start = System.currentTimeMillis()
        while (true) {
            val json = CapabilitiesProvider.autoJs().getExecutionStatusJson(executionId)
            val ok = json.optBoolean("ok", false)
            if (!ok) return ToolResult(false, json)

            val finished = json.optBoolean("finished", false)
            if (finished) return ToolResult(true, json)

            if (timeoutMs != null && System.currentTimeMillis() - start > timeoutMs) {
                val err =
                    JSONObject()
                        .put("ok", false)
                        .put("executionId", executionId)
                        .put(
                            "error",
                            JSONObject()
                                .put("code", "TIMEOUT")
                                .put("message", "Script not finished within timeoutMs=$timeoutMs"),
                        )
                return ToolResult(false, err)
            }

            delay(pollIntervalMs)
        }
    }

    /**
     * Calls existing VLM (ModelClient) to decide next UI action DSL.
     *
     * Returns JSON:
     * - ok: boolean
     * - thinking: string
     * - actionDsl: string  (do(...) or finish(...))
     * - rawContent: string
     */
    suspend fun callVlmNextAction(
        messages: List<ChatMessage>,
        screenshotBase64: String,
    ): ToolResult {
        val result = vlmClient.request(messages, screenshotBase64)

        return when (result) {
            is ModelResult.Success -> {
                val r = result.response
                val json =
                    JSONObject()
                        .put("ok", true)
                        .put("thinking", r.thinking)
                        .put("actionDsl", r.action)
                        .put("rawContent", r.rawContent)
                ToolResult(true, json)
            }

            is ModelResult.Error -> {
                val json =
                    JSONObject()
                        .put("ok", false)
                        .put(
                            "error",
                            JSONObject()
                                .put("code", "VLM_ERROR")
                                .put("message", result.error.message ?: "unknown"),
                        )
                ToolResult(false, json)
            }
        }
    }

    companion object {
        private const val TAG = "PlannerToolDispatcher"
    }
}