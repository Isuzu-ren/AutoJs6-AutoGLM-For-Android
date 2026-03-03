package com.kevinluo.autoglm

import android.content.Context
import com.kevinluo.autoglm.action.ActionHandler
import com.kevinluo.autoglm.agent.PhoneAgent
import com.kevinluo.autoglm.agent.PhoneAgentListener
import com.kevinluo.autoglm.app.AppResolver
import com.kevinluo.autoglm.device.DeviceExecutor
import com.kevinluo.autoglm.history.HistoryManager
import com.kevinluo.autoglm.input.TextInputManager
import com.kevinluo.autoglm.model.ModelClient
import com.kevinluo.autoglm.model.ModelConfig
import com.kevinluo.autoglm.planner.PlannerConfig
import com.kevinluo.autoglm.planner.PlannerOrchestrator
import com.kevinluo.autoglm.planner.deepseek.DeepSeekPlannerClient
import com.kevinluo.autoglm.planner.tools.PlannerToolDispatcher
import com.kevinluo.autoglm.screenshot.ScreenshotService
import com.kevinluo.autoglm.settings.SettingsManager
import com.kevinluo.autoglm.ui.FloatingWindowService
import com.kevinluo.autoglm.util.HumanizedSwipeGenerator
import com.kevinluo.autoglm.util.Logger
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import com.kevinluo.autoglm.config.SystemPrompts

/**
 * Centralized component manager for dependency injection and lifecycle management.
 * Provides a single point of access for all major components in the application.
 *
 * This class ensures:
 * - Proper dependency injection
 * - Lifecycle-aware component management
 * - Clean separation of concerns
 *
 */
class ComponentManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "ComponentManager"

        @Volatile
        private var instance: ComponentManager? = null

        /**
         * Gets the singleton instance of ComponentManager.
         *
         * @param context Application context
         * @return ComponentManager instance
         */
        fun getInstance(context: Context): ComponentManager = instance ?: synchronized(this) {
            instance ?: ComponentManager(context.applicationContext).also { instance = it }
        }

        /**
         * Clears the singleton instance.
         * Should be called when the application is being destroyed.
         */
        fun clearInstance() {
            synchronized(this) {
                instance?.cleanup()
                instance = null
            }
        }
    }

    // Settings manager - singleton instance
    val settingsManager: SettingsManager by lazy {
        SettingsManager.getInstance(context)
    }

    // History manager - always available
    val historyManager: HistoryManager by lazy {
        HistoryManager.getInstance(context)
    }

    // User service reference - set when Shizuku connects
    private var userService: IUserService? = null

    // Lazily initialized components that depend on UserService
    private var deviceExecutorInternal: DeviceExecutor? = null
    private var textInputManagerInternal: TextInputManager? = null
    private var screenshotServiceInternal: ScreenshotService? = null
    private var actionHandlerInternal: ActionHandler? = null
    private var phoneAgentInternal: PhoneAgent? = null

    // Components that don't depend on UserService
    private var modelClientInternal: ModelClient? = null
    private var appResolverInternal: AppResolver? = null
    private var swipeGeneratorInternal: HumanizedSwipeGenerator? = null

    // Planner components (DeepSeek)
    private var plannerClientInternal: DeepSeekPlannerClient? = null
    private var plannerOrchestratorInternal: PlannerOrchestrator? = null
    private var plannerToolDispatcherInternal: PlannerToolDispatcher? = null

    /**
     * Checks if the UserService is connected.
     */
    val isServiceConnected: Boolean
        get() = userService != null

    /**
     * Gets the DeviceExecutor instance.
     * Requires UserService to be connected.
     */
    val deviceExecutor: DeviceExecutor?
        get() = deviceExecutorInternal

    /**
     * Gets the ScreenshotService instance.
     * Requires UserService to be connected.
     */
    val screenshotService: ScreenshotService?
        get() = screenshotServiceInternal

    /**
     * Gets the ActionHandler instance.
     * Requires UserService to be connected.
     */
    val actionHandler: ActionHandler?
        get() = actionHandlerInternal

    /**
     * Gets the PhoneAgent instance.
     * Requires UserService to be connected.
     */
    val phoneAgent: PhoneAgent?
        get() = phoneAgentInternal

    /**
     * Gets the ModelClient instance.
     * Creates a new instance if config has changed.
     */
    val modelClient: ModelClient
        get() {
            val config = settingsManager.getModelConfig()
            if (modelClientInternal == null || modelConfigChanged(config)) {
                modelClientInternal = ModelClient(config)
            }
            return modelClientInternal!!
        }

    /**
     * PlannerOrchestrator (DeepSeek) instance.
     * Only available when:
     * - UserService connected (needs ScreenshotService)
     * - PlannerConfig.enabled == true
     */
    val plannerOrchestrator: PlannerOrchestrator?
        get() = plannerOrchestratorInternal

    /**
     * Gets the AppResolver instance.
     */
    val appResolver: AppResolver
        get() {
            if (appResolverInternal == null) {
                appResolverInternal = AppResolver(context.packageManager)
            }
            return appResolverInternal!!
        }

    /**
     * Gets the HumanizedSwipeGenerator instance.
     */
    val swipeGenerator: HumanizedSwipeGenerator
        get() {
            if (swipeGeneratorInternal == null) {
                swipeGeneratorInternal = HumanizedSwipeGenerator()
            }
            return swipeGeneratorInternal!!
        }

    // Track current model config for change detection
    private var currentModelConfig: ModelConfig? = null

    // Track current planner config for change detection (so toggling settings re-inits)
    private var currentPlannerConfig: PlannerConfig? = null

    /**
     * Called when UserService connects.
     * Initializes all service-dependent components.
     *
     * @param service The connected UserService
     */
    fun onServiceConnected(service: IUserService) {
        Logger.i(TAG, "UserService connected, initializing components")
        userService = service
        initializeServiceDependentComponents()
    }

    /**
     * Called when UserService disconnects.
     * Cleans up service-dependent components.
     */
    fun onServiceDisconnected() {
        Logger.i(TAG, "UserService disconnected, cleaning up components")
        userService = null
        cleanupServiceDependentComponents()
    }

    /**
     * Initializes components that depend on UserService.
     */
    private fun initializeServiceDependentComponents() {
        val service = userService ?: return

        // Create DeviceExecutor
        deviceExecutorInternal = DeviceExecutor(service)

        // Create TextInputManager
        textInputManagerInternal = TextInputManager(service)

        // Create ScreenshotService with floating window controller provider
        // Use a provider function so it can get the current instance dynamically
        screenshotServiceInternal = ScreenshotService(service) { FloatingWindowService.getInstance() }

        // Create ActionHandler with floating window provider to hide window during touch operations
        actionHandlerInternal =
            ActionHandler(
                deviceExecutor = deviceExecutorInternal!!,
                appResolver = appResolver,
                swipeGenerator = swipeGenerator,
                textInputManager = textInputManagerInternal!!,
                floatingWindowProvider = { FloatingWindowService.getInstance() },
            )

        // Create PhoneAgent
        val agentConfig = settingsManager.getAgentConfig()
        phoneAgentInternal =
            PhoneAgent(
                modelClient = modelClient,
                actionHandler = actionHandlerInternal!!,
                screenshotService = screenshotServiceInternal!!,
                config = agentConfig,
                historyManager = historyManager,
                plannerOrchestrator = plannerOrchestratorInternal,
            )

        // Init planner (optional)
        initializePlannerComponentsIfEnabled()

        Logger.i(TAG, "All service-dependent components initialized")
    }

    /**
     * Initializes planner components if PlannerConfig.enabled is true.
     *
     * NOTE:
     * - Planner uses its own config (PlannerConfig), independent from VLM ModelConfig.
     * - Planner needs ScreenshotService + VLM ModelClient, so must be initialized after them.
     */
    private fun initializePlannerComponentsIfEnabled() {
        val plannerConfig = settingsManager.getPlannerConfig()
        val changed = currentPlannerConfig != plannerConfig
        if (changed) currentPlannerConfig = plannerConfig

        if (!plannerConfig.enabled) {
            // ensure released
            plannerClientInternal?.cancelCurrentRequest()
            plannerClientInternal = null
            plannerOrchestratorInternal = null
            plannerToolDispatcherInternal = null
            Logger.i(TAG, "Planner disabled; planner components cleared")
            return
        }

        // Create / recreate if missing or config changed
        if (plannerClientInternal == null || plannerOrchestratorInternal == null || plannerToolDispatcherInternal == null || changed) {
            val httpClient =
                OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build()

            plannerClientInternal =
                DeepSeekPlannerClient(
                    okHttpClient = httpClient,
                    apiKeyProvider = { settingsManager.getPlannerConfig().apiKey },
                    baseUrlProvider = { settingsManager.getPlannerConfig().baseUrl },
                    modelProvider = { settingsManager.getPlannerConfig().modelName },
                )

            plannerToolDispatcherInternal =
                PlannerToolDispatcher(
                    screenshotService = requireNotNull(screenshotServiceInternal),
                    vlmClient = modelClient,
                )

            val vlmSystemPromptProvider: () -> String = {
                val lang = settingsManager.getAgentConfig().language
                SystemPrompts.getPrompt(lang)
            }

            plannerOrchestratorInternal =
                PlannerOrchestrator(
                    plannerClient = plannerClientInternal!!,
                    toolDispatcher = plannerToolDispatcherInternal!!,
                    vlmSystemPromptProvider = vlmSystemPromptProvider,
                )

            Logger.i(TAG, "Planner components initialized: model=${plannerConfig.modelName}, baseUrl=${plannerConfig.baseUrl}")
        }
    }

    /**
     * Cleans up components that depend on UserService.
     */
    private fun cleanupServiceDependentComponents() {
        phoneAgentInternal?.cancel()
        phoneAgentInternal = null
        actionHandlerInternal = null
        screenshotServiceInternal = null
        textInputManagerInternal = null
        deviceExecutorInternal = null

        // planner components also rely on screenshot service; clear them too
        plannerClientInternal?.cancelCurrentRequest()
        plannerClientInternal = null
        plannerOrchestratorInternal = null
        plannerToolDispatcherInternal = null
        currentPlannerConfig = null

        Logger.i(TAG, "Service-dependent components cleaned up")
    }

    /**
     * Reinitializes the PhoneAgent with updated configuration.
     * Call this after settings have been changed.
     *
     * Note: This will NOT reinitialize if a task is currently running or paused,
     * to prevent accidentally cancelling user tasks.
     */
    fun reinitializeAgent() {
        if (userService == null) {
            Logger.w(TAG, "Cannot reinitialize agent: UserService not connected")
            return
        }

        // Safety check: don't reinitialize while a task is active
        phoneAgentInternal?.let { agent ->
            if (agent.isRunning() || agent.isPaused()) {
                Logger.w(TAG, "Cannot reinitialize agent: task is currently active (state: ${agent.getState()})")
                return
            }
        }

        // Cancel any running task (should be IDLE at this point, but just in case)
        phoneAgentInternal?.cancel()

        // Recreate model client with new config
        modelClientInternal = null

        // Recreate PhoneAgent
        val agentConfig = settingsManager.getAgentConfig()
        phoneAgentInternal =
            PhoneAgent(
                modelClient = modelClient,
                actionHandler = actionHandlerInternal!!,
                screenshotService = screenshotServiceInternal!!,
                config = agentConfig,
                historyManager = historyManager,
                plannerOrchestrator = plannerOrchestratorInternal,
            )

        // Re-init planner too (might have been enabled/disabled/changed)
        initializePlannerComponentsIfEnabled()

        Logger.i(TAG, "PhoneAgent reinitialized with new configuration")
    }

    /**
     * Sets the listener for PhoneAgent events.
     *
     * @param listener The listener to set
     */
    fun setPhoneAgentListener(listener: PhoneAgentListener?) {
        phoneAgentInternal?.setListener(listener)
    }

    /**
     * Sets the confirmation callback for ActionHandler.
     *
     * @param callback The callback to set
     */
    fun setConfirmationCallback(callback: ActionHandler.ConfirmationCallback?) {
        actionHandlerInternal?.setConfirmationCallback(callback)
    }

    /**
     * Checks if the model config has changed.
     */
    private fun modelConfigChanged(newConfig: ModelConfig): Boolean {
        val changed = currentModelConfig != newConfig
        if (changed) {
            currentModelConfig = newConfig
        }
        return changed
    }

    /**
     * Cleans up all components.
     * Should be called when the application is being destroyed.
     */
    fun cleanup() {
        Logger.i(TAG, "Cleaning up all components")
        cleanupServiceDependentComponents()
        modelClientInternal = null
        appResolverInternal = null
        swipeGeneratorInternal = null
        currentModelConfig = null
    }

    /**
     * Gets the current state summary for debugging.
     */
    fun getStateSummary(): String = buildString {
        appendLine("ComponentManager State:")
        appendLine("  - UserService connected: $isServiceConnected")
        appendLine("  - DeviceExecutor: ${if (deviceExecutorInternal != null) "initialized" else "null"}")
        appendLine("  - ScreenshotService: ${if (screenshotServiceInternal != null) "initialized" else "null"}")
        appendLine("  - ActionHandler: ${if (actionHandlerInternal != null) "initialized" else "null"}")
        appendLine("  - PhoneAgent: ${if (phoneAgentInternal != null) "initialized" else "null"}")
        appendLine("  - ModelClient: ${if (modelClientInternal != null) "initialized" else "null"}")
        appendLine("  - AppResolver: ${if (appResolverInternal != null) "initialized" else "null"}")
        appendLine("  - SwipeGenerator: ${if (swipeGeneratorInternal != null) "initialized" else "null"}")
        appendLine("  - PlannerClient: ${if (plannerClientInternal != null) "initialized" else "null"}")
        appendLine("  - PlannerOrchestrator: ${if (plannerOrchestratorInternal != null) "initialized" else "null"}")
    }
}