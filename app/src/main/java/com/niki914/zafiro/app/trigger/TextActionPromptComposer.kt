package com.niki914.zafiro.app.trigger

/**
 * 文本处理动作的提示词拼接。纯函数，无 Context 依赖。
 *
 * 产物对用户可见（不走 <zfr-*> 隐藏块）：选中文本以 markdown 引用块呈现，
 * 后接动作提示词；[NOTE] 行向模型声明这段文本的来源是复制而非用户手打。
 */
object TextActionPromptComposer {

    private const val NOTE_LINE = "> [NOTE]: The text below was copied from another app."

    /**
     * @param template 动作提示词，如 "Translate the following text into English."
     * @param selectedText 选中的文本；null 或空白时返回 [template] 本身。
     */
    fun compose(template: String, selectedText: String?): String {
        if (selectedText.isNullOrBlank()) return template.trim()
        val quoted = selectedText.trim().lineSequence()
            .joinToString("\n") { line -> "> $line" }
        return "$NOTE_LINE\n>\n$quoted\n\n${template.trim()}"
    }
}
