package com.kevinluo.autoglm.planner

/**
 * Planner (DeepSeek) config.
 *
 * Kept separate from ModelConfig because VLM and Planner are different backends.
 */
data class PlannerConfig(
    val enabled: Boolean = false,

    // DeepSeek API base URL, default official
    val baseUrl: String = "https://api.deepseek.com",

    // DeepSeek API key (store in encrypted prefs)
    val apiKey: String = "EMPTY",

    // Model name (deepseek-chat for text-only tool calling)
    val modelName: String = "deepseek-chat",

    // Sampling
    val temperature: Float = 0.2f,

    // Planner loop guard
    val maxPlannerTurns: Int = 20,

    // watch_script_until_finished polling
    val pollIntervalMs: Long = 1000L,
    val timeoutMs: Long = 120_000L,
)