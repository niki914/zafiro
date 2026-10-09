package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.McpHostService
import com.niki914.zafiro.api.McpHostStatus
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.model.RuntimeMcpHostConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class McpHostToolItemUi(
    val name: String,
    val displayName: String,
    val description: String,
    val isDangerous: Boolean = false,
)

data class McpHostSettingsUiState(
    val isLoading: Boolean = false,
    val enabled: Boolean = false,
    val status: McpHostStatus = McpHostStatus.Stopped,
    val port: Int = RuntimeMcpHostConfig.DEFAULT_PORT,
    val portInput: String = RuntimeMcpHostConfig.DEFAULT_PORT.toString(),
    val portError: String? = null,
    val host: String = RuntimeMcpHostConfig.DEFAULT_HOST,
    val tokenInput: String = "",
    val tokenVisible: Boolean = false,
    val exposedTools: Set<String> = RuntimeMcpHostConfig.DEFAULT_EXPOSED_TOOLS,
    val tools: List<McpHostToolItemUi> = emptyList(),
)

sealed interface McpHostSettingsIntent {
    data object Load : McpHostSettingsIntent
    data class ToggleServer(val enabled: Boolean) : McpHostSettingsIntent
    data class PortChanged(val portString: String) : McpHostSettingsIntent
    data class TokenChanged(val token: String) : McpHostSettingsIntent
    data object ToggleTokenVisibility : McpHostSettingsIntent
    data class ToggleTool(val toolName: String, val checked: Boolean) : McpHostSettingsIntent
}

sealed interface McpHostSettingsEffect

class McpHostSettingsViewModel :
    ComposeMVIViewModel<McpHostSettingsIntent, McpHostSettingsUiState, McpHostSettingsEffect>() {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_McpHostSettingsViewModel"
        private const val MIN_PORT = 1024
        private const val MAX_PORT = 65535

        private val SAFE_BUILTIN_TOOL_ORDER = listOf(
            "screen_operation_accessibility",
            "screen_operation_shell",
            "screenshot",
            "launch_app",
            "find_installed_apps",
            "open_uri",
            "load_skill",
            "notify",
            "view_image",
            "memory",
        )

        private val TOOL_DISPLAY_INFO = mapOf(
            "screen_operation_accessibility" to ("Accessibility UI Tree" to "Read UI tree, perform clicks, scrolls, and text input"),
            "screen_operation_shell" to ("Shell Coordinates" to "Inject taps and swipes using screen pixel coordinates"),
            "screenshot" to ("Screen Capture" to "Capture screen images for visual inspection"),
            "launch_app" to ("Launch Application" to "Open installed Android apps by package name"),
            "find_installed_apps" to ("Find Installed Apps" to "Query list of installed packages on device"),
            "open_uri" to ("Open URI / Deep Link" to "Open web URLs or app deep links via Android Intent"),
            "load_skill" to ("Load Skill" to "Read skill instructions and documentation"),
            "notify" to ("System Notification" to "Post notifications to the Android status bar"),
            "view_image" to ("View Image" to "Read and inspect image files from storage"),
            "memory" to ("Agent Memory" to "Store and recall persistent memory facts"),
            "terminal" to ("Terminal Shell" to "Execute shell commands with root/shizuku privileges"),
            "execute_python" to ("Execute Python" to "Execute arbitrary Python code in python process"),
            "py_meta_tools" to ("Python Meta Tools" to "Dynamically register and manage custom python tools"),
        )
    }

    private val hostService = requireService<McpHostService>()

    init {
        viewModelScope.launch {
            hostService.status.collectLatest { liveStatus ->
                updateState { copy(status = liveStatus) }
            }
        }
        viewModelScope.launch {
            XRepo.mcpHost.configFlow.collectLatest { config ->
                updateState {
                    copy(
                        enabled = config.enabled,
                        port = config.port,
                        host = config.host,
                        exposedTools = config.exposedTools,
                    )
                }
            }
        }
    }

    override fun initUiState(): McpHostSettingsUiState = McpHostSettingsUiState()

    override suspend fun handleIntent(intent: McpHostSettingsIntent) {
        when (intent) {
            McpHostSettingsIntent.Load -> load()
            is McpHostSettingsIntent.ToggleServer -> toggleServer(intent.enabled)
            is McpHostSettingsIntent.PortChanged -> updatePort(intent.portString)
            is McpHostSettingsIntent.TokenChanged -> updateToken(intent.token)
            McpHostSettingsIntent.ToggleTokenVisibility -> updateState {
                copy(tokenVisible = !tokenVisible)
            }
            is McpHostSettingsIntent.ToggleTool -> toggleTool(intent.toolName, intent.checked)
        }
    }

    private suspend fun load() {
        val config = XRepo.mcpHost.get()
        val allTools = buildSortedToolList()
        updateState {
            copy(
                enabled = config.enabled,
                port = config.port,
                portInput = config.port.toString(),
                portError = null,
                host = config.host,
                tokenInput = config.bearerToken,
                exposedTools = config.exposedTools,
                tools = allTools,
            )
        }
    }

    private suspend fun toggleServer(enabled: Boolean) {
        Logger.i(LOG_TAG, "user toggled MCP server enabled: $enabled")
        XRepo.mcpHost.setEnabled(enabled)
    }

    private suspend fun updatePort(portString: String) {
        val parsedPort = portString.trim().toIntOrNull()
        if (parsedPort == null || parsedPort !in MIN_PORT..MAX_PORT) {
            updateState {
                copy(
                    portInput = portString,
                    portError = "Port must be between $MIN_PORT and $MAX_PORT",
                )
            }
            return
        }
        updateState {
            copy(
                portInput = portString,
                portError = null,
            )
        }
        XRepo.mcpHost.setPort(parsedPort)
    }

    private suspend fun updateToken(token: String) {
        updateState { copy(tokenInput = token) }
        XRepo.mcpHost.setBearerToken(token.trim())
    }

    private suspend fun toggleTool(toolName: String, checked: Boolean) {
        val current = currentState.exposedTools
        val updated = if (checked) {
            current + toolName
        } else {
            current - toolName
        }
        XRepo.mcpHost.setExposedTools(updated)
    }

    private suspend fun buildSortedToolList(): List<McpHostToolItemUi> {
        val builtinRegistry = BuiltinToolRegistry.default()
        val registeredBuiltins = builtinRegistry.all()
            .filter { !it.name.startsWith("mcp__") }
            .associateBy { it.name }

        val safeTools = SAFE_BUILTIN_TOOL_ORDER.mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[name] ?: (tool.name to tool.description)
                McpHostToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = false,
                )
            }
        }

        val terminalTool = registeredBuiltins["terminal"]?.let { tool ->
            val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[tool.name] ?: (tool.name to tool.description)
            listOf(
                McpHostToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = true,
                )
            )
        }.orEmpty()

        val pythonTools = listOf("execute_python", "py_meta_tools").mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[name] ?: (tool.name to tool.description)
                McpHostToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = true,
                )
            }
        }

        val customPyTools = runCatching { XRepo.customPyTools.list() }
            .getOrDefault(emptyList())
            .filter { !it.name.startsWith("mcp__") }
            .sortedBy { it.name }
            .map { custom ->
                McpHostToolItemUi(
                    name = custom.name,
                    displayName = custom.name,
                    description = custom.description.ifBlank { "Custom Python tool" },
                    isDangerous = false,
                )
            }

        return safeTools + terminalTool + pythonTools + customPyTools
    }
}
