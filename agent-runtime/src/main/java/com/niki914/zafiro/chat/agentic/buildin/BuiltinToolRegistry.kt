package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.FindInstalledAppsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PyDownloadFileBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PyMetaToolsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenOperationAccessibilityBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenOperationShellBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.TerminalBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ViewImageBuiltin

class BuiltinToolRegistry(
    private val tools: List<BuiltinTool>,
) {
    fun all(): List<BuiltinTool> = tools

    fun find(name: String): BuiltinTool? {
        return tools.firstOrNull { it.name == name }
    }

    companion object {
        fun default(): BuiltinToolRegistry = BuiltinToolRegistry(
            listOf(
                ExecutePythonBuiltin(),
                LaunchAppBuiltin(),
                PyMetaToolsBuiltin(),
                PyDownloadFileBuiltin(),
                MemoryBuiltin(),
                NotifyBuiltin(),
                OpenUriBuiltin(),
                LoadSkillBuiltin(),
                TerminalBuiltin(),
                FindInstalledAppsBuiltin(),
                ScreenOperationAccessibilityBuiltin(),
                ScreenOperationShellBuiltin(),
                ViewImageBuiltin(),
            )
        )
    }
}
