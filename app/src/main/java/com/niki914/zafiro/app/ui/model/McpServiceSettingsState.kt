package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.McpService
import com.niki914.zafiro.api.McpServiceStatus
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.requireService
import com.niki914.zafiro.settings.model.RuntimeMcpServiceConfig
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class McpServiceToolItemUi(
    val name: String,
    val displayName: String,
    val description: String,
    val isDangerous: Boolean = false,
)

data class McpServiceSettingsUiState(
    val isLoading: Boolean = false,
    val enabled: Boolean = false,
    val status: McpServiceStatus = McpServiceStatus.Stopped,
    val port: Int = RuntimeMcpServiceConfig.DEFAULT_PORT,
    val portInput: String = RuntimeMcpServiceConfig.DEFAULT_PORT.toString(),
    val portError: String? = null,
    val host: String = RuntimeMcpServiceConfig.DEFAULT_HOST,
    val tokenInput: String = "",
    val tokenVisible: Boolean = false,
    val exposedTools: Set<String> = RuntimeMcpServiceConfig.DEFAULT_EXPOSED_TOOLS,
    val keepScreenOn: Boolean = true,
    val tools: List<McpServiceToolItemUi> = emptyList(),
)

sealed interface McpServiceSettingsIntent {
    data object Load : McpServiceSettingsIntent
    data class ToggleServer(val enabled: Boolean) : McpServiceSettingsIntent
    data class PortChanged(val portString: String) : McpServiceSettingsIntent
    data class TokenChanged(val token: String) : McpServiceSettingsIntent
    data object ToggleTokenVisibility : McpServiceSettingsIntent
    data class ToggleTool(val toolName: String, val checked: Boolean) : McpServiceSettingsIntent
    data class ToggleKeepScreenOn(val enabled: Boolean) : McpServiceSettingsIntent
}

sealed interface McpServiceSettingsEffect

class McpServiceSettingsViewModel :
    ComposeMVIViewModel<McpServiceSettingsIntent, McpServiceSettingsUiState, McpServiceSettingsEffect>() {

    private companion object {
        private const val LOG_TAG = "niki914_zafiro_McpServiceSettingsViewModel"
        private const val MIN_PORT = RuntimeMcpServiceConfig.MIN_PORT
        private const val MAX_PORT = RuntimeMcpServiceConfig.MAX_PORT

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
            "shell" to ("Shell" to "Local Android persistent shell environment"),
            "ssh" to ("SSH" to "Remote SSH persistent terminal"),
            "execute_python" to ("Execute Python" to "Execute arbitrary Python code in python process"),
            "py_meta_tools" to ("Python Meta Tools" to "Dynamically register and manage custom python tools"),
        )
    }

    private val hostService = requireService<McpService>()

    init {
        viewModelScope.launch {
            hostService.status.collectLatest { liveStatus ->
                updateState { copy(status = liveStatus) }
            }
        }
        viewModelScope.launch {
            XRepo.mcpService.configFlow.collectLatest { config ->
                updateState {
                    copy(
                        enabled = config.enabled,
                        port = config.port,
                        host = config.host,
                        exposedTools = config.exposedTools,
                        keepScreenOn = config.keepScreenOn,
                    )
                }
            }
        }
    }

    override fun initUiState(): McpServiceSettingsUiState = McpServiceSettingsUiState()

    override suspend fun handleIntent(intent: McpServiceSettingsIntent) {
        when (intent) {
            McpServiceSettingsIntent.Load -> load()
            is McpServiceSettingsIntent.ToggleServer -> toggleServer(intent.enabled)
            is McpServiceSettingsIntent.PortChanged -> updatePort(intent.portString)
            is McpServiceSettingsIntent.TokenChanged -> updateToken(intent.token)
            McpServiceSettingsIntent.ToggleTokenVisibility -> updateState {
                copy(tokenVisible = !tokenVisible)
            }

            is McpServiceSettingsIntent.ToggleTool -> toggleTool(intent.toolName, intent.checked)
            is McpServiceSettingsIntent.ToggleKeepScreenOn ->
                XRepo.mcpService.setKeepScreenOnWhileRunning(intent.enabled)
        }
    }

    private suspend fun load() {
        val config = XRepo.mcpService.get()
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
                keepScreenOn = config.keepScreenOn,
                tools = allTools,
            )
        }
    }

    private suspend fun toggleServer(enabled: Boolean) {
        Logger.i(LOG_TAG, "user toggled MCP server enabled: $enabled")
        XRepo.mcpService.setEnabled(enabled)
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
        XRepo.mcpService.setPort(parsedPort)
    }

    private suspend fun updateToken(token: String) {
        updateState { copy(tokenInput = token) }
        XRepo.mcpService.setBearerToken(token.trim())
    }

    private suspend fun toggleTool(toolName: String, checked: Boolean) {
        val current = currentState.exposedTools
        val updated = if (checked) {
            current + toolName
        } else {
            current - toolName
        }
        XRepo.mcpService.setExposedTools(updated)
    }

    private suspend fun buildSortedToolList(): List<McpServiceToolItemUi> {
        val builtinRegistry = BuiltinToolRegistry.default()
        val registeredBuiltins = builtinRegistry.all()
            .filter { !it.name.startsWith("mcp__") }
            .associateBy { it.name }

        val safeTools = SAFE_BUILTIN_TOOL_ORDER.mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[name]
                    ?: (tool.name to tool.description)
                McpServiceToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = false,
                )
            }
        }

        val sessionTools = listOf("shell", "ssh").mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[tool.name]
                    ?: (tool.name to tool.description)
                McpServiceToolItemUi(
                    name = tool.name,
                    displayName = displayTitle,
                    description = displayDesc,
                    isDangerous = true,
                )
            }
        }

        val pythonTools = listOf("execute_python", "py_meta_tools").mapNotNull { name ->
            registeredBuiltins[name]?.let { tool ->
                val (displayTitle, displayDesc) = TOOL_DISPLAY_INFO[name]
                    ?: (tool.name to tool.description)
                McpServiceToolItemUi(
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
                McpServiceToolItemUi(
                    name = custom.name,
                    displayName = custom.name,
                    description = custom.description.ifBlank { "Custom Python tool" },
                    isDangerous = false,
                )
            }

        return safeTools + sessionTools + pythonTools + customPyTools
    }
}
