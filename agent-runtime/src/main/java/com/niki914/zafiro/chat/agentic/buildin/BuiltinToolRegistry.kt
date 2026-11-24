package com.niki914.zafiro.chat.agentic.buildin

import com.niki914.zafiro.chat.agentic.buildin.impl.BoardBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.CreateSkillBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.EngageBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ExamplesBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.DatetimeBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.MakeCardBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.RssReadBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScheduleBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.WebFetchBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.WebSearchBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ExecutePythonBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.FindInstalledAppsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LaunchAppBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LoadSkillBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.MemoryBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.AgentBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ClipboardBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.LockScreenBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ComposeMessageBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.HttpRequestBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.TakeOverBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.DeviceInfoBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.NotifyBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.WaitBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenUriBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.PyMetaToolsBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.SaveFileBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.ScreenRoutineBuiltin
import com.niki914.zafiro.chat.agentic.buildin.impl.OpenBuiltin
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
                WaitBuiltin(),
                DeviceInfoBuiltin(),
                ClipboardBuiltin(),
                HttpRequestBuiltin(),
                TakeOverBuiltin(),
                AgentBuiltin(),
                BoardBuiltin(),
                EngageBuiltin(),
                ExamplesBuiltin(),
                LockScreenBuiltin(),
                ComposeMessageBuiltin(),
                OpenUriBuiltin(),
                LaunchAppBuiltin(),
                LoadSkillBuiltin(),
                ShellBuiltin(),
                SshBuiltin(),
                FindInstalledAppsBuiltin(),
                ScreenRoutineBuiltin(),
                SaveFileBuiltin(),
                CreateSkillBuiltin(),
                WebFetchBuiltin(),
                WebSearchBuiltin(),
                RssReadBuiltin(),
                DatetimeBuiltin(),
                MakeCardBuiltin(),
                ScheduleBuiltin(),
                ScreenOperationBuiltin(),
                ScreenshotBuiltin(),
                OpenBuiltin(),
                ScreensBuiltin(),
                ViewImageBuiltin(),
            )
        )
    }
}
