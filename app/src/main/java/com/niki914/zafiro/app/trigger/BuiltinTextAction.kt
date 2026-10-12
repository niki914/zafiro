package com.niki914.zafiro.app.trigger

import androidx.annotation.StringRes
import com.niki914.zafiro.app.R

/**
 * 文本快捷动作定义。
 *
 * 动作具备单动作粒度的执行配置：
 * - [requiresScreenshot]：是否在此动作触发时获取屏幕截图作为视觉附件输入；
 * - [runInBackground]：是否在后台静默运行（true: 静默不跳主 UI，完成发即时通知；false: 尝试置前主 UI）。
 */
data class BuiltinTextAction(
    val id: String,
    val title: String,
    @StringRes val titleRes: Int,
    val promptTemplate: String,
    val requiresScreenshot: Boolean,
    val runInBackground: Boolean,
) {
    companion object {
        val EXPLAIN_SCREEN = BuiltinTextAction(
            id = "explain_screen",
            title = "Explain Screen",
            titleRes = R.string.text_action_explain_screen,
            promptTemplate = "Explain what is currently on the screen based on the screenshot and selected text.",
            requiresScreenshot = true,
            runInBackground = false,
        )

        val TRANSLATE = BuiltinTextAction(
            id = "translate",
            title = "Translate",
            titleRes = R.string.text_action_translate,
            promptTemplate = "Translate the following text into English (or Chinese if already English).",
            requiresScreenshot = false,
            runInBackground = true,
        )

        val ALL: List<BuiltinTextAction> = listOf(
            EXPLAIN_SCREEN,
            TRANSLATE,
        )
    }
}
