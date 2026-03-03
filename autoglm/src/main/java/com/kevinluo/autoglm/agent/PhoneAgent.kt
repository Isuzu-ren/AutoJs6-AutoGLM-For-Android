package com.kevinluo.autoglm.agent

import com.kevinluo.autoglm.action.ActionHandler
import com.kevinluo.autoglm.action.ActionParseException
import com.kevinluo.autoglm.action.ActionParser
import com.kevinluo.autoglm.action.AgentAction
import com.kevinluo.autoglm.action.CoordinateOutOfRangeException
import com.kevinluo.autoglm.config.SystemPrompts
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.model.ModelClient
import com.kevinluo.autoglm.model.ModelResult
import com.kevinluo.autoglm.planner.PlannerOrchestrator
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.util.ErrorHandler
import com.kevinluo.autoglm.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Configuration for the PhoneAgent.
 */
data class AgentConfig(
    val maxSteps: Int = 100,
    val language: String = "cn",
    val verbose: Boolean = true,
    val screenshotDelayMs: Long = 2000L,
)

/**
 * Result of a single step execution.
 */
data class StepResult(
    val success: Boolean,
    val finished: Boolean,
    val action: AgentAction?,
    val thinking: String,
    val message: String?,
    // Hint to pass to the model in the next step
    val nextStepHint: String? = null,
    // Indicates step was interrupted by pause
    val paused: Boolean = false,
)

/**
 * Result of a complete task execution.
 */
data class TaskResult(val success: Boolean, val message: String, val stepCount: Int)

/**
 * Listener interface for PhoneAgent events.
 * Allows UI components to receive updates about task execution progress.
 */
interface PhoneAgentListener {
    fun onStepStarted(stepNumber: Int)

    fun onThinkingUpdate(thinking: String)

    fun onActionExecuted(action: AgentAction)

    fun onTaskCompleted(message: String)

    fun onTaskFailed(error: String)

    fun onScreenshotStarted()

    fun onScreenshotCompleted()

    fun onFloatingWindowRefreshNeeded()

    fun onTaskPaused(stepNumber: Int) {} // Optional: called when task is paused

    fun onTaskResumed(stepNumber: Int) {} // Optional: called when task is resumed
}

/**
 * Represents the current state of the agent.
 */
enum class AgentState {
    IDLE,
    RUNNING,
    PAUSED, // Task is paused, waiting to be resumed
    CANCELLED,
}

/**
 * Core agent class responsible for coordinating the automation flow.
 * Manages task execution, model communication, and action handling.
 *
 */
class PhoneAgent(
    private val modelClient: ModelClient,
    private val actionHandler: ActionHandler,
    private val screenshotService: ScreenshotService,
    private val config: AgentConfig = AgentConfig(),
    private val historyManager: HistoryManager? = null,

    // NEW: DeepSeek planner orchestrator (optional)
    private val plannerOrchestrator: PlannerOrchestrator? = null,
) {
    // Private properties
    private val context: AtomicReference<AgentContext?> = AtomicReference(null)
    private val _state = MutableStateFlow(AgentState.IDLE)
    private val stateLock = Any() // Lock for atomic state transitions
    private val actionExecuted: AtomicBoolean =
        AtomicBoolean(
            false,
        ) // Track if action has been executed in current step
    private var listener: PhoneAgentListener? = null
    private var currentStepNumber: Int = 0

    /**
     * Atomically updates state if current value matches expected.
     *
     * @param expect The expected current state
     * @param update The new state to set
     * @return true if update was successful, false if current state didn't match
     */
    private fun compareAndSetState(expect: AgentState, update: AgentState): Boolean = synchronized(stateLock) {
        if (_state.value == expect) {
            _state.value = update
            true
        } else {
            false
        }
    }

    /**
     * Gets the cancellation message based on language setting.
     *
     * @return Localized cancellation message
     */
    private fun getCancellationMessage(): String =
        if (config.language.lowercase() == "en" || config.language.lowercase() == "english") {
            CANCELLATION_MESSAGE_EN
        } else {
            CANCELLATION_MESSAGE
        }

    /**
     * Sets the listener for agent events.
     *
     * @param listener The listener to receive agent events, or null to remove
     */
    fun setListener(listener: PhoneAgentListener?) {
        this.listener = listener
    }

    /**
     * Gets the current state of the agent.
     *
     * @return Current AgentState
     */
    fun getState(): AgentState = _state.value

    /**
     * Checks if a task is currently running.
     *
     * @return true if a task is running, false otherwise
     */
    fun isRunning(): Boolean = _state.value == AgentState.RUNNING

    /**
     * Checks if a task is currently paused.
     *
     * @return true if a task is paused, false otherwise
     */
    fun isPaused(): Boolean = _state.value == AgentState.PAUSED

    /**
     * Pauses the currently running task.
     * The task will pause immediately and cancel any ongoing network request.
     * When resumed, the current step will be retried (unless action was already executed).
     *
     * @return true if pause was initiated, false if task is not running
     */
    fun pause(): Boolean {
        Logger.i(TAG, "Pause requested, current state: ${_state.value}")

        if (compareAndSetState(AgentState.RUNNING, AgentState.PAUSED)) {
            // Cancel any ongoing model request immediately
            modelClient.cancelCurrentRequest()
            plannerOrchestrator?.cancelCurrentRequest()

            // If action hasn't been executed yet, we'll retry this step when resumed
            // The step number will be decremented in executeStep when it detects pause
            val willRetry = !actionExecuted.get()
            Logger.i(TAG, "Task paused at step $currentStepNumber, willRetryStep=$willRetry")
            listener?.onTaskPaused(currentStepNumber)
            return true
        }

        Logger.w(TAG, "Cannot pause: task is not running (state: ${_state.value})")
        return false
    }

    /**
     * Resumes a paused task.
     *
     * @return true if resume was successful, false if task is not paused
     */
    fun resume(): Boolean {
        Logger.i(TAG, "Resume requested, current state: ${_state.value}")

        if (compareAndSetState(AgentState.PAUSED, AgentState.RUNNING)) {
            Logger.i(TAG, "Task resumed from step $currentStepNumber")
            listener?.onTaskResumed(currentStepNumber)
            return true
        }

        Logger.w(TAG, "Cannot resume: task is not paused (state: ${_state.value})")
        return false
    }

    /**
     * Waits while the task is paused.
     * Returns immediately if not paused or if cancelled.
     * Uses StateFlow to suspend until state changes, avoiding polling.
     */
    private suspend fun waitWhilePaused() {
        if (_state.value == AgentState.PAUSED) {
            _state.first { it != AgentState.PAUSED }
        }
    }

    /**
     * Validates a task description.
     *
     * @param task The task description to validate
     * @return true if valid, false if empty or whitespace only
     *
     */
    fun isValidTask(task: String): Boolean = task.isNotBlank()

    /**
     * Runs a complete task from start to finish.
     * Executes steps until the task is completed, cancelled, or max steps reached.
     *
     * @param task The natural language task description
     * @return TaskResult containing success status, message, and step count
     *
     */
    suspend fun run(task: String): TaskResult = coroutineScope {
        // Validate task description (Requirement 1.2)
        if (!isValidTask(task)) {
            Logger.w(TAG, "Task validation failed: empty or whitespace only")
            return@coroutineScope TaskResult(
                success = false,
                message = "Task description cannot be empty or whitespace only",
                stepCount = 0,
            )
        }

        // Check if already running (Requirement 1.3)
        if (!compareAndSetState(AgentState.IDLE, AgentState.RUNNING)) {
            Logger.w(TAG, "Task rejected: another task is already running")
            return@coroutineScope TaskResult(
                success = false,
                message = "A task is already running. Please wait or cancel it first.",
                stepCount = 0,
            )
        }

        currentStepNumber = 0

        // Initialize context with system prompt based on language setting
        val systemPrompt = SystemPrompts.getPrompt(config.language)
        context.set(AgentContext(systemPrompt))

        // Start history recording
        historyManager?.startTask(task)

        Logger.logTaskStart(task)

        // Track if we've already completed history to avoid duplicates
        var historyCompleted = false
        val cancellationMsg = getCancellationMessage()

        try {
            var stepCount = 0
            var lastMessage = ""
            var success = true
            var nextStepHint: String? = null

            // Execute steps until finished or max steps reached
            // When maxSteps is 0, run indefinitely (no limit)
            while (config.maxSteps == 0 || stepCount < config.maxSteps) {
                ensureActive()

                if (_state.value == AgentState.CANCELLED) {
                    Logger.i(TAG, "Task cancelled by user at step $stepCount")
                    if (!historyCompleted) {
                        historyManager?.completeTask(false, cancellationMsg)
                        historyCompleted = true
                    }
                    // Don't call listener here - let the caller handle UI updates
                    return@coroutineScope TaskResult(
                        success = false,
                        message = cancellationMsg,
                        stepCount = stepCount,
                    )
                }

                // Execute single step, passing hint from previous step if any
                val stepResult =
                    executeStep(
                        task = if (stepCount == 0) task else null,
                        hint = nextStepHint,
                    )

                // Handle pause - wait for resume and retry the step
                if (stepResult.paused) {
                    Logger.i(TAG, "Step returned paused, waiting for resume...")
                    waitWhilePaused()

                    // Check if cancelled while paused
                    if (_state.value == AgentState.CANCELLED) {
                        Logger.i(TAG, "Task cancelled while paused")
                        if (!historyCompleted) {
                            historyManager?.completeTask(false, cancellationMsg)
                            historyCompleted = true
                        }
                        return@coroutineScope TaskResult(
                            success = false,
                            message = cancellationMsg,
                            stepCount = stepCount,
                        )
                    }

                    // Resume - continue loop to retry the step (stepCount not incremented)
                    Logger.i(TAG, "Resumed, retrying step...")
                    continue
                }

                stepCount++

                // Store hint for next step
                nextStepHint = stepResult.nextStepHint

                if (!stepResult.success) {
                    // Check if this failure is due to cancellation
                    if (_state.value == AgentState.CANCELLED) {
                        Logger.i(TAG, "Task cancelled at step $stepCount")
                        if (!historyCompleted) {
                            historyManager?.completeTask(false, cancellationMsg)
                            historyCompleted = true
                        }
                        return@coroutineScope TaskResult(
                            success = false,
                            message = cancellationMsg,
                            stepCount = stepCount,
                        )
                    }

                    success = false
                    lastMessage = stepResult.message ?: "Step execution failed"
                    Logger.w(TAG, "Step $stepCount failed: $lastMessage")
                    // Don't call listener here - let the caller handle UI updates
                    break
                }

                if (stepResult.finished) {
                    lastMessage = stepResult.message ?: "Task completed"
                    Logger.i(TAG, "Task finished at step $stepCount: $lastMessage")
                    // Don't call listener here - let the caller handle UI updates
                    break
                }

                lastMessage = stepResult.message ?: ""
            }

            // Check if max steps reached (only when maxSteps > 0)
            if (config.maxSteps > 0 && stepCount >= config.maxSteps) {
                lastMessage = "Maximum steps (${config.maxSteps}) reached"
                Logger.w(TAG, lastMessage)
                success = false
            }

            // Complete history recording (only if not already done)
            if (!historyCompleted) {
                historyManager?.completeTask(success, lastMessage)
                historyCompleted = true
            }

            Logger.logTaskComplete(success, lastMessage, stepCount)

            TaskResult(
                success = success,
                message = lastMessage,
                stepCount = stepCount,
            )
        } catch (e: CancellationException) {
            _state.value = AgentState.CANCELLED
            Logger.i(TAG, "Task cancelled via coroutine cancellation")
            if (!historyCompleted) {
                historyManager?.completeTask(false, cancellationMsg)
            }
            TaskResult(
                success = false,
                message = cancellationMsg,
                stepCount = currentStepNumber,
            )
        } catch (e: Exception) {
            // Always check if cancelled first - user cancellation takes priority over any other error
            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled, ignoring exception: ${e.message}")
                if (!historyCompleted) {
                    historyManager?.completeTask(false, cancellationMsg)
                }
                return@coroutineScope TaskResult(
                    success = false,
                    message = cancellationMsg,
                    stepCount = currentStepNumber,
                )
            }

            val handledError = ErrorHandler.handleUnknownError("Task execution error", e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            if (!historyCompleted) {
                historyManager?.completeTask(false, handledError.userMessage)
            }
            TaskResult(
                success = false,
                message = handledError.userMessage,
                stepCount = currentStepNumber,
            )
        } finally {
            _state.value = AgentState.IDLE
        }
    }

    /**
     * Executes a single step of the task.
     *
     * When plannerOrchestrator != null:
     * - This step will ask planner for next DSL action and then execute it.
     * - Screenshots are captured ONLY when planner decides they are needed (A1).
     *
     * When plannerOrchestrator == null:
     * - Keeps old behavior: always capture screenshot and ask VLM directly.
     */
    private suspend fun executeStep(task: String?, hint: String? = null): StepResult {
        // Wait if paused (check at the beginning of each step)
        waitWhilePaused()

        // Reset action executed flag at the start of each step
        actionExecuted.set(false)

        currentStepNumber++
        Logger.logStep(currentStepNumber, task ?: "continue")
        listener?.onStepStarted(currentStepNumber)

        val ctx =
            context.get() ?: run {
                Logger.e(TAG, "Agent context not initialized")
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = "Agent context not initialized",
                )
            }

        val cancellationMsg = getCancellationMessage()

        // Fast cancel check
        if (_state.value == AgentState.CANCELLED) {
            Logger.i(TAG, "Task cancelled at start of step")
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = cancellationMsg,
            )
        }

        // Pause check
        if (_state.value == AgentState.PAUSED) {
            Logger.i(TAG, "Task paused at start of step, will retry step")
            currentStepNumber--
            return StepResult(
                success = true,
                finished = false,
                action = null,
                thinking = "",
                message = PAUSE_MESSAGE,
                paused = true,
            )
        }

        return if (plannerOrchestrator != null) {
            executeStepViaPlanner(plannerOrchestrator, task, hint, cancellationMsg)
        } else {
            executeStepViaVlmLegacy(ctx, task, hint, cancellationMsg)
        }
    }

    /**
     * Legacy mode: always take screenshot then call VLM directly (original behavior).
     */
    private suspend fun executeStepViaVlmLegacy(
        ctx: AgentContext,
        task: String?,
        hint: String?,
        cancellationMsg: String,
    ): StepResult {
        try {
            if (config.screenshotDelayMs > 0) {
                Logger.d(TAG, "Waiting ${config.screenshotDelayMs}ms before screenshot...")
                kotlinx.coroutines.delay(config.screenshotDelayMs)
            }

            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled after screenshot delay")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Task paused before screenshot, will retry step")
                currentStepNumber--
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }

            Logger.d(TAG, "Capturing screenshot...")
            listener?.onScreenshotStarted()
            val screenshot = screenshotService.capture()
            listener?.onScreenshotCompleted()
            Logger.logScreenshot(screenshot.width, screenshot.height, screenshot.isSensitive)

            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled after screenshot capture")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Task paused after screenshot, will retry step with fresh screenshot")
                currentStepNumber--
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }

            historyManager?.setCurrentScreenshot(screenshot.base64Data, screenshot.width, screenshot.height)

            val userText =
                when {
                    task != null -> "任务: $task\n当前屏幕截图如下:"
                    hint != null -> "上一步执行结果: $hint\n继续执行任务，当前屏幕截图如下:"
                    else -> "继续执行任务，当前屏幕截图如下:"
                }
            ctx.addUserMessage(userText)

            Logger.d(TAG, "Requesting model response...")
            val modelResult = modelClient.request(ctx.getMessages(), screenshot.base64Data)

            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled during/after model request")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Task paused during/after model request, will retry step")
                currentStepNumber--
                ctx.removeLastUserMessage()
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }

            return when (modelResult) {
                is ModelResult.Success -> {
                    val response = modelResult.response
                    Logger.logThinking(response.thinking)
                    Logger.logModelAction(response.action)
                    listener?.onThinkingUpdate(response.thinking)

                    if (_state.value == AgentState.CANCELLED) {
                        Logger.i(TAG, "Task cancelled before action execution")
                        return StepResult(false, true, null, response.thinking, cancellationMsg)
                    }
                    if (_state.value == AgentState.PAUSED) {
                        Logger.i(TAG, "Task paused before action execution, will retry step")
                        currentStepNumber--
                        ctx.removeLastUserMessage()
                        return StepResult(true, false, null, response.thinking, PAUSE_MESSAGE, paused = true)
                    }

                    if (response.action.isBlank()) {
                        val retryResult = retryForEmptyAction(ctx, screenshot.base64Data, response.thinking)
                        if (_state.value == AgentState.CANCELLED) {
                            return StepResult(false, true, null, retryResult?.thinking ?: response.thinking, cancellationMsg)
                        }
                        if (_state.value == AgentState.PAUSED) {
                            currentStepNumber--
                            ctx.removeLastUserMessage()
                            return StepResult(true, false, null, retryResult?.thinking ?: response.thinking, PAUSE_MESSAGE, paused = true)
                        }

                        if (retryResult == null) {
                            historyManager?.recordStep(
                                stepNumber = currentStepNumber,
                                thinking = response.thinking,
                                action = null,
                                actionDescription = "无操作",
                                success = false,
                                message = "模型响应中没有操作（已重试${MAX_EMPTY_ACTION_RETRIES}次）",
                            )
                            return StepResult(false, false, null, response.thinking, "模型响应中没有操作（已重试${MAX_EMPTY_ACTION_RETRIES}次）")
                        }

                        return executeAction(
                            retryResult.action,
                            retryResult.thinking,
                            screenshot.originalWidth,
                            screenshot.originalHeight,
                        )
                    }

                    ctx.addAssistantMessage(response.rawContent)
                    executeAction(
                        response.action,
                        response.thinking,
                        screenshot.originalWidth,
                        screenshot.originalHeight,
                    )
                }

                is ModelResult.Error -> {
                    if (_state.value == AgentState.CANCELLED) {
                        Logger.i(TAG, "Task cancelled, ignoring model error")
                        StepResult(false, true, null, "", cancellationMsg)
                    } else if (_state.value == AgentState.PAUSED) {
                        Logger.i(TAG, "Model request cancelled due to pause, will retry step")
                        currentStepNumber--
                        ctx.removeLastUserMessage()
                        StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
                    } else {
                        val handledError = ErrorHandler.handleNetworkError(modelResult.error)
                        Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError))
                        historyManager?.recordStep(
                            stepNumber = currentStepNumber,
                            thinking = "",
                            action = null,
                            actionDescription = "模型错误",
                            success = false,
                            message = handledError.userMessage,
                        )
                        StepResult(false, false, null, "", handledError.userMessage)
                    }
                }
            }
        } catch (e: CancellationException) {
            Logger.i(TAG, "Step cancelled via coroutine cancellation")
            return StepResult(false, true, null, "", cancellationMsg)
        } catch (e: Exception) {
            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled, exception ignored: ${e.message}")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Exception during paused state, will retry step: ${e.message}")
                currentStepNumber--
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }
            val handledError = ErrorHandler.handleUnknownError("Step execution error", e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            return StepResult(false, false, null, "", handledError.userMessage)
        }
    }

    /**
     * Planner mode: ask DeepSeek planner for next DSL, then execute.
     *
     * NOTE:
     * - Planner internally decides whether to take screenshot / call VLM / run scripts.
     * - Here we only execute the final DSL returned by planner.
     */
    private suspend fun executeStepViaPlanner(
        planner: PlannerOrchestrator,
        task: String?,
        hint: String?,
        cancellationMsg: String,
    ): StepResult {
        try {
            // Optional delay before step (keeps existing behavior pacing)
            if (config.screenshotDelayMs > 0) {
                Logger.d(TAG, "Waiting ${config.screenshotDelayMs}ms before planner step...")
                kotlinx.coroutines.delay(config.screenshotDelayMs)
            }

            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled before planner request")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Task paused before planner request, will retry step")
                currentStepNumber--
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }

            // Ask planner for next DSL action
            val plannerOut = planner.planNext(
                task = task ?: "continue",
                hint = hint,
            )

            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled during/after planner request")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Task paused during/after planner request, will retry step")
                currentStepNumber--
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }

            // Planner might decide to answer directly (rare). Treat as finish.
            if (plannerOut.plannerFinalAnswer != null) {
                val msg = plannerOut.plannerFinalAnswer
                historyManager?.recordStep(
                    stepNumber = currentStepNumber,
                    thinking = "",
                    action = null,
                    actionDescription = "planner_final",
                    success = true,
                    message = msg,
                )
                return StepResult(
                    success = true,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = msg,
                )
            }

            val actionDsl = plannerOut.nextActionDsl?.trim().orEmpty()
            if (!plannerOut.ok || actionDsl.isBlank()) {
                val err = plannerOut.debug ?: "Planner returned no action"
                historyManager?.recordStep(
                    stepNumber = currentStepNumber,
                    thinking = "",
                    action = null,
                    actionDescription = "planner_error",
                    success = false,
                    message = err,
                )
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = err,
                )
            }

            // Execute the DSL
            // Since planner uses VLM for UI actions, DSL should be parseable by existing ActionParser.
            // We still need screenWidth/Height; in legacy we used screenshot.originalWidth/Height.
            // Here we capture a lightweight screenshot ONLY to get dimensions for coordinate scaling.
            // (This does NOT go to model; it's just for executing 0-999 coords.)
            listener?.onScreenshotStarted()
            val screenshot = screenshotService.capture()
            listener?.onScreenshotCompleted()

            historyManager?.setCurrentScreenshot(screenshot.base64Data, screenshot.width, screenshot.height)

            // No ctx.addUserMessage/assistantMessage here: planner owns its own conversation.
            // But you can still save the DSL to history as "assistant" if you want.

            return executeAction(
                actionStr = actionDsl,
                thinking = "", // plannerOut doesn't currently expose thinking; you can extend PlannerOrchestrator to include it.
                screenWidth = screenshot.originalWidth,
                screenHeight = screenshot.originalHeight,
            )
        } catch (e: CancellationException) {
            Logger.i(TAG, "Planner step cancelled via coroutine cancellation")
            return StepResult(false, true, null, "", cancellationMsg)
        } catch (e: Exception) {
            if (_state.value == AgentState.CANCELLED) {
                Logger.i(TAG, "Task cancelled, planner exception ignored: ${e.message}")
                return StepResult(false, true, null, "", cancellationMsg)
            }
            if (_state.value == AgentState.PAUSED) {
                Logger.i(TAG, "Planner exception during paused state, will retry step: ${e.message}")
                currentStepNumber--
                return StepResult(true, false, null, "", PAUSE_MESSAGE, paused = true)
            }
            val handledError = ErrorHandler.handleUnknownError("Planner step execution error", e)
            Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
            return StepResult(false, false, null, "", handledError.userMessage)
        }
    }

    /**
     * Result of requesting model response with retry support.
     *
     * @property thinking The model's thinking/reasoning text
     * @property action The action string from model response
     * @property rawContent The raw response content for context
     * @property retryCount Number of retries performed (0 if succeeded on first try)
     */
    private data class ModelRequestResult(
        val thinking: String,
        val action: String,
        val rawContent: String,
        val retryCount: Int = 0,
    )

    /**
     * Requests model response with automatic retry for empty actions.
     *
     * When the model returns an empty action (thinking only, no do/finish),
     * this method will retry up to [MAX_EMPTY_ACTION_RETRIES] times.
     *
     * @param ctx The agent context containing conversation history
     * @param screenshotBase64 The current screenshot in base64 format
     * @return [ModelRequestResult] on success, null if all retries failed or interrupted
     */
    private suspend fun requestModelWithRetry(ctx: AgentContext, screenshotBase64: String): ModelRequestResult? {
        val initialResult = modelClient.request(ctx.getMessages(), screenshotBase64)

        when (initialResult) {
            is ModelResult.Success -> {
                val response = initialResult.response
                Logger.logThinking(response.thinking)
                Logger.logModelAction(response.action)
                listener?.onThinkingUpdate(response.thinking)

                // If action is not empty, return immediately
                if (response.action.isNotBlank()) {
                    ctx.addAssistantMessage(response.rawContent)
                    return ModelRequestResult(
                        thinking = response.thinking,
                        action = response.action,
                        rawContent = response.rawContent,
                    )
                }

                // Action is empty, need to retry
                Logger.w(TAG, "No action in model response, attempting retry...")
                return retryForEmptyAction(ctx, screenshotBase64, response.thinking)
            }

            is ModelResult.Error -> {
                Logger.e(TAG, "Model request failed: ${initialResult.error.message}")
                return null
            }
        }
    }

    /**
     * Retries model request when initial response had empty action.
     *
     * @param ctx The agent context
     * @param screenshotBase64 The screenshot to resend
     * @param initialThinking The thinking from the initial (failed) response
     * @return [ModelRequestResult] on success, null if all retries exhausted
     */
    private suspend fun retryForEmptyAction(
        ctx: AgentContext,
        screenshotBase64: String,
        initialThinking: String,
    ): ModelRequestResult? {
        var retryCount = 0
        var lastThinking = initialThinking

        while (retryCount < MAX_EMPTY_ACTION_RETRIES) {
            retryCount++
            Logger.i(TAG, "Empty action retry $retryCount/$MAX_EMPTY_ACTION_RETRIES")

            // Check cancellation/pause before retry
            val currentState = _state.value
            if (currentState == AgentState.CANCELLED || currentState == AgentState.PAUSED) {
                Logger.d(TAG, "Retry interrupted by state: $currentState")
                return null
            }

            val retryResult = modelClient.request(ctx.getMessages(), screenshotBase64)

            // Check state after request
            val afterState = _state.value
            if (afterState == AgentState.CANCELLED || afterState == AgentState.PAUSED) {
                Logger.d(TAG, "Retry interrupted after request by state: $afterState")
                return null
            }

            when (retryResult) {
                is ModelResult.Success -> {
                    val response = retryResult.response
                    lastThinking = response.thinking
                    Logger.d(TAG, "Retry $retryCount response action: ${response.action.take(50)}")
                    listener?.onThinkingUpdate(response.thinking)

                    if (response.action.isNotBlank()) {
                        ctx.addAssistantMessage(response.rawContent)
                        Logger.i(TAG, "Got action after $retryCount retries")
                        return ModelRequestResult(
                            thinking = response.thinking,
                            action = response.action,
                            rawContent = response.rawContent,
                            retryCount = retryCount,
                        )
                    }
                }

                is ModelResult.Error -> {
                    Logger.w(TAG, "Retry $retryCount failed: ${retryResult.error.message}")
                    return null
                }
            }
        }

        Logger.w(TAG, "No action after $retryCount retries, lastThinking: ${lastThinking.take(100)}")
        return null
    }

    /**
     * Parses and executes an action from the model response.
     *
     * @param actionStr The action string from model response
     * @param thinking The thinking text from model response
     * @param screenWidth Current screen width in pixels
     * @param screenHeight Current screen height in pixels
     * @return StepResult containing action execution outcome
     */
    private suspend fun executeAction(
        actionStr: String,
        thinking: String,
        screenWidth: Int,
        screenHeight: Int,
    ): StepResult = try {
        Logger.d(TAG, "Parsing action: $actionStr")
        val action = ActionParser.parse(actionStr)
        Logger.logAction(action::class.simpleName ?: "Unknown", actionStr)

        // Notify listener
        listener?.onActionExecuted(action)

        // Mark that we're about to execute the action - after this point, no retry on pause
        actionExecuted.set(true)

        // Execute the action
        Logger.d(TAG, "Executing action...")
        val result = actionHandler.execute(action, screenWidth, screenHeight)
        Logger.d(TAG, "Action result: success=${result.success}, finish=${result.shouldFinish}")

        // Record step in history
        historyManager?.recordStep(
            stepNumber = currentStepNumber,
            thinking = thinking,
            action = action,
            actionDescription = action.formatForDisplay(),
            success = result.success,
            message = result.message,
        )

        // Refresh floating window if needed (e.g., after launching another app)
        if (result.refreshFloatingWindow) {
            Logger.d(TAG, "Refreshing floating window after action")
            listener?.onFloatingWindowRefreshNeeded()
        }

        // Determine if we need to pass a hint to the next step
        // This is used when an action succeeds but needs follow-up (e.g., app not found by package name)
        val nextHint =
            if (result.success && !result.shouldFinish && result.message != null &&
                result.message.contains("请在主屏幕或应用列表中查找")
            ) {
                result.message
            } else {
                null
            }

        StepResult(
            success = result.success,
            finished = result.shouldFinish,
            action = action,
            thinking = thinking,
            message = result.message,
            nextStepHint = nextHint,
        )
    } catch (e: CoordinateOutOfRangeException) {
        // Handle coordinate out of range - return hint for retry
        Logger.w(TAG, "Coordinate out of range: ${e.message}")
        val correctionHint = buildCoordinateCorrectionHint(e, config.language)

        // Record failed step
        historyManager?.recordStep(
            stepNumber = currentStepNumber,
            thinking = thinking,
            action = null,
            actionDescription = "坐标越界: ${e.originalAction}",
            success = false,
            message = correctionHint,
        )

        StepResult(
            // Mark as success to continue, but provide hint
            success = true,
            finished = false,
            action = null,
            thinking = thinking,
            message = correctionHint,
            nextStepHint = correctionHint,
        )
    } catch (e: ActionParseException) {
        val handledError = ErrorHandler.handleParsingError(actionStr, e.message ?: "Unknown parse error", e)
        Logger.e(TAG, ErrorHandler.formatErrorForLog(handledError), e)
        // Record failed step
        historyManager?.recordStep(
            stepNumber = currentStepNumber,
            thinking = thinking,
            action = null,
            actionDescription = "解析错误: $actionStr",
            success = false,
            message = handledError.userMessage,
        )
        StepResult(
            success = false,
            finished = false,
            action = null,
            thinking = thinking,
            message = handledError.userMessage,
        )
    }

    /**
     * Builds a correction hint message for coordinate out of range errors.
     * This hint will be passed to the model in the next step to help it correct the coordinates.
     *
     * @param e The coordinate out of range exception
     * @param language Language code: "cn" for Chinese, "en" for English
     * @return Localized correction hint message
     */
    private fun buildCoordinateCorrectionHint(e: CoordinateOutOfRangeException, language: String): String {
        val invalidDetails =
            e.invalidCoordinates.joinToString(if (language == "en") ", " else "、") {
                "${it.name}=${it.value}"
            }

        return if (language.lowercase() == "en" || language.lowercase() == "english") {
            """【Coordinate Error】The coordinates in your previous action are out of valid range!
Invalid coordinates: $invalidDetails
Original action: ${e.originalAction}

【Important Reminder】The coordinate system starts from top-left (0,0) to bottom-right (999,999).
All coordinate values MUST be between 0 and 999.
Please re-analyze the current screenshot and output correct coordinates (within 0-999 range)."""
        } else {
            """【坐标错误】你上一步输出的操作坐标超出了有效范围！
错误的坐标: $invalidDetails
原始操作: ${e.originalAction}

【重要提醒】坐标系统从左上角 (0,0) 开始到右下角 (999,999) 结束，所有坐标值必须在 0-999 范围内。
请重新分析当前屏幕截图，输出正确的坐标（0-999范围内）。"""
        }
    }

    /**
     * Executes a single step for manual step-by-step control.
     *
     * @param task Optional task description (required for first step)
     * @return StepResult containing step outcome
     *
     */
    suspend fun step(task: String? = null): StepResult {
        // For first step, validate task
        if (context.get() == null) {
            if (task == null || !isValidTask(task)) {
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = "Task description required for first step",
                )
            }

            // Check if already running
            if (!compareAndSetState(AgentState.IDLE, AgentState.RUNNING)) {
                return StepResult(
                    success = false,
                    finished = false,
                    action = null,
                    thinking = "",
                    message = "A task is already running",
                )
            }

            // Initialize context with system prompt based on language setting
            val systemPrompt = SystemPrompts.getPrompt(config.language)
            context.set(AgentContext(systemPrompt))
            currentStepNumber = 0
        }

        if (_state.value == AgentState.CANCELLED) {
            _state.value = AgentState.IDLE
            return StepResult(
                success = false,
                finished = true,
                action = null,
                thinking = "",
                message = getCancellationMessage(),
            )
        }

        try {
            val result = executeStep(task)

            // If finished or failed, reset state
            if (result.finished || !result.success) {
                _state.value = AgentState.IDLE
            }

            return result
        } catch (e: Exception) {
            _state.value = AgentState.IDLE

            // Check if cancelled
            if (_state.value == AgentState.CANCELLED) {
                return StepResult(
                    success = false,
                    finished = true,
                    action = null,
                    thinking = "",
                    message = getCancellationMessage(),
                )
            }

            return StepResult(
                success = false,
                finished = false,
                action = null,
                thinking = "",
                message = "Step execution error: ${e.message}",
            )
        }
    }

    /**
     * Cancels the currently running task.
     * Uses compareAndSet to ensure state transition only happens when task is RUNNING or PAUSED.
     *
     */
    fun cancel() {
        Logger.i(TAG, "Cancel requested, current state: ${_state.value}")

        // Try to transition from RUNNING or PAUSED to CANCELLED
        var transitioned = compareAndSetState(AgentState.RUNNING, AgentState.CANCELLED)
        if (!transitioned) {
            transitioned = compareAndSetState(AgentState.PAUSED, AgentState.CANCELLED)
        }

        // Cancel any ongoing model request
        modelClient.cancelCurrentRequest()
        plannerOrchestrator?.cancelCurrentRequest()

        Logger.i(TAG, "Task cancelled, state transitioned: $transitioned, current state: ${_state.value}")
    }

    /**
     * Resets the agent to initial state.
     * Clears context and prepares for a new task.
     * Note: Does NOT reset the cancelled flag - that's handled at the start of run()
     * to ensure cancellation is properly detected even after reset.
     *
     */
    fun reset() {
        Logger.i(TAG, "Resetting agent, current state: ${_state.value}")

        // Cancel any ongoing model request
        modelClient.cancelCurrentRequest()

        // Clear state
        context.get()?.reset()
        context.set(null)
        currentStepNumber = 0
        _state.value = AgentState.IDLE

        Logger.i(TAG, "Agent reset complete, state: ${_state.value}")
    }

    /**
     * Gets the current step number.
     *
     * @return Current step number in the task execution
     */
    fun getCurrentStepNumber(): Int = currentStepNumber

    /**
     * Gets the current context for inspection.
     *
     * @return Current AgentContext or null if not initialized
     */
    fun getContext(): AgentContext? = context.get()

    /**
     * Sets a custom system prompt.
     * Must be called before starting a task.
     *
     * @param prompt The custom system prompt to use
     */
    fun setSystemPrompt(prompt: String) {
        if (_state.value == AgentState.IDLE) {
            context.set(AgentContext(prompt))
        }
    }

    // Companion object placed at the bottom following code style guidelines (Requirement 3.1)
    companion object {
        private const val TAG = "PhoneAgent"

        /** Cancellation message in Chinese. */
        const val CANCELLATION_MESSAGE = "任务已被用户取消"

        /** Cancellation message in English. */
        const val CANCELLATION_MESSAGE_EN = "Task cancelled by user"

        /** Pause message in Chinese. */
        const val PAUSE_MESSAGE = "任务已暂停"

        /** Pause message in English. */
        const val PAUSE_MESSAGE_EN = "Task paused"

        /** Maximum retries when model returns empty action. */
        private const val MAX_EMPTY_ACTION_RETRIES = 3
    }
}