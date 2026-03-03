package com.kevinluo.autoglm.planner.deepseek

/**
 * Minimal DeepSeek Chat Completions models for tool-calling.
 *
 * Notes:
 * - DeepSeek API is OpenAI-compatible in broad strokes, but details may vary.
 * - Keep parsing tolerant: ignore unknown fields.
 */
data class DsChatRequest(
    val model: String,
    val messages: List<DsMessage>,
    val tools: List<DsTool>? = null,
    val tool_choice: Any? = "auto",
    val temperature: Double? = 0.2,
)

data class DsTool(
    val type: String = "function",
    val function: DsFunctionDef,
)

data class DsFunctionDef(
    val name: String,
    val description: String? = null,
    val parameters: Map<String, Any?>? = null,
)

data class DsMessage(
    val role: String,
    val content: String? = null,
    val tool_calls: List<DsToolCall>? = null,
    val tool_call_id: String? = null,
)

data class DsToolCall(
    val id: String,
    val type: String,
    val function: DsToolCallFunction,
)

data class DsToolCallFunction(
    val name: String,
    val arguments: String,
)

data class DsChatResponse(
    val id: String? = null,
    val choices: List<DsChoice> = emptyList(),
)

data class DsChoice(
    val index: Int? = null,
    val message: DsMessage? = null,
    val finish_reason: String? = null,
)