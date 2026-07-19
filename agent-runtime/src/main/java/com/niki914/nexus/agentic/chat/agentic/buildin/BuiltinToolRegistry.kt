package com.niki914.nexus.agentic.chat.agentic.buildin

import com.niki914.nexus.agentic.chat.agentic.buildin.impl.CreateCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.MemorizeBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.ReadCustomToolBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.GestureBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.KeyEventBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.NodeActionBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.ScreenContentBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SearchAppsBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.SshTerminalBuiltin
import com.niki914.nexus.agentic.chat.agentic.buildin.impl.TerminalBuiltin

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
                CreateCustomToolBuiltin(),
                LaunchAppBuiltin(),
                MemorizeBuiltin(),
                NotifyBuiltin(),
                OpenUriBuiltin(),
                ReadCustomToolBuiltin(),
                LoadSkillBuiltin(),
                TerminalBuiltin(),
                SshTerminalBuiltin(),
                SearchAppsBuiltin(),
                ScreenContentBuiltin(),
                NodeActionBuiltin(),
                GestureBuiltin(),
                KeyEventBuiltin(),
            )
        )
    }
}
