package com.niki914.zafiro.app.trigger

/**
 * 文本处理动作的提示词拼接。纯函数，无 Context 依赖。
 *
 * 产物对用户可见（不走 <zfr-*> 隐藏块）：NOTE 行在前声明语境，选中文本以 markdown 引用块呈现，
 * 动作提示词模板在最后。
 */
object TextActionPromptComposer {

    private const val NOTE_LINE = "> [NOTE]: The text below was copied from another app."
    private const val CAPTURE_FAILED_NOTE_LINE =
        "> [NOTE]: Zafiro's screen capture failed; no screenshot is attached."

    /**
     * @param template 动作提示词，如 "Translate the following text into English."
     * @param selectedText 选中的文本；null 或空白时不出现引用块。
     * @param captureFailed 动作要求截图但抓图失败时为 true，产出对应的 NOTE 行。
     * @param captureFailure 失败原因（如无障碍服务不可用），模型据此转告用户该如何补救；
     *   null 或空白时只保留通用文案。
     */
    fun compose(
        template: String,
        selectedText: String?,
        captureFailed: Boolean = false,
        captureFailure: String? = null,
    ): String {
        val notes = buildList {
            if (captureFailed) {
                val detail = captureFailure?.takeIf { it.isNotBlank() }
                add(if (detail == null) CAPTURE_FAILED_NOTE_LINE else "$CAPTURE_FAILED_NOTE_LINE $detail")
            }
            if (!selectedText.isNullOrBlank()) add(NOTE_LINE)
        }
        if (notes.isEmpty()) return template.trim()
        val body = if (selectedText.isNullOrBlank()) {
            notes
        } else {
            val quoted = selectedText.trim().lineSequence()
                .joinToString("\n") { line -> "> $line" }
            notes + ">" + quoted
        }
        return body.joinToString("\n") + "\n\n${template.trim()}"
    }
}
