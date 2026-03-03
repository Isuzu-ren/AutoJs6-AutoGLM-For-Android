package com.kevinluo.autoglm.planner

import com.kevinluo.autoglm.agent.AgentContext
import com.kevinluo.autoglm.config.PlannerPrompts
import com.kevinluo.autoglm.model.ChatMessage
import com.kevinluo.autoglm.planner.deepseek.DeepSeekPlannerClient
import com.kevinluo.autoglm.planner.deepseek.DsFunctionDef
import com.kevinluo.autoglm.planner.deepseek.DsMessage
import com.kevinluo.autoglm.planner.deepseek.DsTool
import com.kevinluo.autoglm.planner.tools.PlannerToolDispatcher
import org.json.JSONObject

class PlannerOrchestrator(
    private val plannerClient: DeepSeekPlannerClient,
    private val toolDispatcher: PlannerToolDispatcher,
    private val vlmSystemPromptProvider: () -> String,
) {
    data class PlannerStepOutput(
        val ok: Boolean,
        val nextActionDsl: String? = null,
        val plannerFinalAnswer: String? = null,
        val debug: String? = null,
    )

    private val tools: List<DsTool> = buildTools()

    private fun buildTools(): List<DsTool> {
        fun params(obj: JSONObject): Map<String, Any?> = obj.toFlatMap()

        return listOf(
            DsTool(
                function =
                    DsFunctionDef(
                        name = "get_screenshot",
                        description = "Capture a screenshot of the current device screen. Use only when visual context is needed.",
                        parameters =
                            params(
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("reason", JSONObject().put("type", "string")),
                                    )
                                    .put("required", org.json.JSONArray()),
                            ),
                    ),
            ),
            DsTool(
                function =
                    DsFunctionDef(
                        name = "list_scripts",
                        description = "List runnable scripts in AutoJs workspace root directory (.js/.auto).",
                        parameters = params(JSONObject().put("type", "object").put("properties", JSONObject()).put("required", org.json.JSONArray())),
                    ),
            ),
            DsTool(
                function =
                    DsFunctionDef(
                        name = "run_script",
                        description = "Run a script by absolute path with optional JSON params. Only .js/.auto allowed.",
                        parameters =
                            params(
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("path", JSONObject().put("type", "string"))
                                            .put(
                                                "params",
                                                JSONObject()
                                                    .put("type", "object")
                                                    .put("additionalProperties", true),
                                            ),
                                    )
                                    .put("required", org.json.JSONArray().put("path")),
                            ),
                    ),
            ),
            DsTool(
                function =
                    DsFunctionDef(
                        name = "watch_script_until_finished",
                        description = "Poll script execution status until finished=true and return final status JSON.",
                        parameters =
                            params(
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("executionId", JSONObject().put("type", "integer"))
                                            .put("pollIntervalMs", JSONObject().put("type", "integer").put("default", 1000))
                                            .put("timeoutMs", JSONObject().put("type", "integer")),
                                    )
                                    .put("required", org.json.JSONArray().put("executionId")),
                            ),
                    ),
            ),
            DsTool(
                function =
                    DsFunctionDef(
                        name = "call_vlm_next_action",
                        description = "Ask VLM to propose next UI action in AutoGLM DSL (do(...) or finish(...)).",
                        parameters =
                            params(
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("task", JSONObject().put("type", "string"))
                                            .put("hint", JSONObject().put("type", "string"))
                                            .put("forceFinishAllowed", JSONObject().put("type", "boolean").put("default", true)),
                                    )
                                    .put("required", org.json.JSONArray()),
                            ),
                    ),
            ),
        )
    }

    suspend fun planNext(
        task: String,
        hint: String? = null,
        maxPlannerTurns: Int = 20,
    ): PlannerStepOutput {
        val messages = mutableListOf<DsMessage>()
        messages += DsMessage(role = "system", content = PlannerPrompts.plannerCn())
        messages += DsMessage(role = "user", content = buildUserTaskText(task, hint))

        // var lastScreenshotBase64: String? = null

        repeat(maxPlannerTurns) { turn ->
            val assistant = plannerClient.chat(messages = messages, tools = tools)
            messages += assistant

            val toolCalls = assistant.tool_calls
            if (toolCalls.isNullOrEmpty()) {
                val content = assistant.content?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    return PlannerStepOutput(ok = true, plannerFinalAnswer = content, debug = "turn=$turn no tool_calls")
                }
                return PlannerStepOutput(ok = false, debug = "turn=$turn empty assistant content and no tool_calls")
            }

            for (tc in toolCalls) {
                val fnName = tc.function.name
                val argsText = tc.function.arguments
                val args = runCatching { JSONObject(argsText.ifBlank { "{}" }) }.getOrElse { JSONObject() }

                val toolResult: PlannerToolDispatcher.ToolResult =
                    when (fnName) {
                        "get_screenshot" -> {
                            val reason = args.optString("reason", null)
                            // toolDispatcher.getScreenshot(reason).also {
                            //     lastScreenshotBase64 = it.content.optString("base64Data", null)
                            // }
                            toolDispatcher.getScreenshot(reason)
                        }

                        "list_scripts" -> toolDispatcher.listScripts()

                        "run_script" -> {
                            val path = args.getString("path")
                            val paramsObj = args.optJSONObject("params")
                            toolDispatcher.runScript(path, paramsObj)
                        }

                        "watch_script_until_finished" -> {
                            val executionId = args.getInt("executionId")
                            val poll = args.optLong("pollIntervalMs", 1000L)
                            val timeout = if (args.has("timeoutMs")) args.optLong("timeoutMs") else null
                            toolDispatcher.watchScriptUntilFinished(executionId, pollIntervalMs = poll, timeoutMs = timeout)
                        }

                        "call_vlm_next_action" -> {
                            val vlmTask = args.optString("task", task)
                            val vlmHint = args.optString("hint", null)

                            // Planner never receives screenshot base64.
                            // We capture screenshot internally and only send base64 to VLM.
                            val screenshotRes = toolDispatcher.captureScreenshotBase64ForVlm(reason = "call_vlm_next_action")
                            if (!screenshotRes.ok) {
                                screenshotRes
                            } else {
                                val screenshotBase64 = screenshotRes.content.optString("base64Data", "").trim()
                                if (screenshotBase64.isBlank()) {
                                    val err =
                                        JSONObject()
                                            .put("ok", false)
                                            .put(
                                                "error",
                                                JSONObject()
                                                    .put("code", "MISSING_SCREENSHOT")
                                                    .put("message", "Failed to capture screenshot for VLM."),
                                            )
                                    PlannerToolDispatcher.ToolResult(false, err)
                                } else {
                                    val vlmMessages = buildVlmMessages(vlmTask, vlmHint)
                                    val vlmRes = toolDispatcher.callVlmNextAction(vlmMessages, screenshotBase64)

                                    val actionDsl = vlmRes.content.optString("actionDsl", "").trim()
                                    if (vlmRes.ok && actionDsl.isNotBlank()) {
                                        return PlannerStepOutput(ok = true, nextActionDsl = actionDsl, debug = "turn=$turn via VLM")
                                    }
                                    vlmRes
                                }
                            }

                            // // Prefer explicit screenshotBase64; otherwise use last captured screenshot.
                            // val screenshotBase64 =
                            //     args.optString("screenshotBase64", null)
                            //         ?: lastScreenshotBase64
                            //
                            // if (screenshotBase64.isNullOrBlank()) {
                            //     val err =
                            //         JSONObject()
                            //             .put("ok", false)
                            //             .put(
                            //                 "error",
                            //                 JSONObject()
                            //                     .put("code", "MISSING_SCREENSHOT")
                            //                     .put("message", "call_vlm_next_action requires screenshotBase64; call get_screenshot first."),
                            //             )
                            //     PlannerToolDispatcher.ToolResult(false, err)
                            // } else {
                            //     val vlmMessages = buildVlmMessages(vlmTask, vlmHint)
                            //     val vlmRes = toolDispatcher.callVlmNextAction(vlmMessages, screenshotBase64)
                            //
                            //     val actionDsl = vlmRes.content.optString("actionDsl", "").trim()
                            //     if (vlmRes.ok && actionDsl.isNotBlank()) {
                            //         return PlannerStepOutput(ok = true, nextActionDsl = actionDsl, debug = "turn=$turn via VLM")
                            //     }
                            //     vlmRes
                            // }
                        }

                        else -> {
                            val err =
                                JSONObject()
                                    .put("ok", false)
                                    .put("error", JSONObject().put("code", "UNKNOWN_TOOL").put("message", "Unknown tool: $fnName"))
                            PlannerToolDispatcher.ToolResult(false, err)
                        }
                    }

                messages +=
                    DsMessage(
                        role = "tool",
                        tool_call_id = tc.id,
                        content = toolResult.content.toString(),
                    )
            }
        }

        return PlannerStepOutput(ok = false, debug = "planner exceeded maxPlannerTurns=$maxPlannerTurns")
    }

    fun cancelCurrentRequest() {
        plannerClient.cancelCurrentRequest()
    }
    private fun buildUserTaskText(task: String, hint: String?): String {
        return buildString {
            append("任务：").append(task)
            if (!hint.isNullOrBlank()) {
                append("\n上一步结果：").append(hint)
            }
            append("\n请根据需要调用工具：脚本优先；需要 UI 操作时直接 call_vlm_next_action（内部会截图）；只有需要获取屏幕尺寸/敏感标记等元信息时才调用 get_screenshot。")
        }
    }

    /**
     * VLM messages: reuse existing AgentContext/ChatMessage.
     *
     * IMPORTANT: VLM must output ONLY one line DSL:
     * - do(...)
     * - finish(message="...")
     */
    private fun buildVlmMessages(task: String, hint: String?): List<ChatMessage> {
        val ctx = AgentContext(vlmSystemPromptProvider())
        val userText =
            buildString {
                append("任务: ").append(task)
                if (!hint.isNullOrBlank()) append("\n上一步结果: ").append(hint)
                append("\n请根据当前截图输出下一步操作。只允许输出一行 AutoGLM DSL：do(...) 或 finish(message=\"...\")。")
            }
        ctx.addUserMessage(userText)
        return ctx.getMessages()
    }

    /**
     * DeepSeek schema builder helper.
     * (DeepSeekPlannerClient will wrap this map into JSONObject anyway.)
     */
    private fun JSONObject.toFlatMap(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        val it = keys()
        while (it.hasNext()) {
            val k = it.next()
            map[k] = get(k)
        }
        return map
    }
}