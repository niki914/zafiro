package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.FindInstalledAppsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PyMetaToolsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenOperationBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreensBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenshotBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ShellBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SshBuiltin
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
                PyMetaToolsBuiltin(),
                MemoryBuiltin(),
                NotifyBuiltin(),
                LoadSkillBuiltin(),
                ShellBuiltin(),
                SshBuiltin(),
                FindInstalledAppsBuiltin(),
                ScreenOperationBuiltin(),
                ScreenshotBuiltin(),
                OpenBuiltin(),
                ScreensBuiltin(),
                ViewImageBuiltin(),
            )
        )
    }
}
