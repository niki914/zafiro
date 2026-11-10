package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.FindInstalledAppsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PyMetaToolsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenOperationBuiltin__V2
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreensBuiltin__V2
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenshotBuiltin__V2
import com.niki914.zafiro.chat.agentic.buildin.impl.ShellBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ShowBuiltin__V2
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
                ScreenOperationBuiltin__V2(),
                ScreenshotBuiltin__V2(),
                ShowBuiltin__V2(),
                ScreensBuiltin__V2(),
                ViewImageBuiltin(),
            )
        )
    }
}
