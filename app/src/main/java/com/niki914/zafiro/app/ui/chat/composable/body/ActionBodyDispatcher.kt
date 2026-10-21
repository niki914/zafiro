package com.niki914.zafiro.app.ui.chat.composable.body

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.niki914.zafiro.app.ui.chat.composable.body.custom.CodeToolBody
import com.niki914.zafiro.app.ui.chat.composable.body.custom.ErrorDetailBody
import com.niki914.zafiro.app.ui.chat.composable.body.custom.ImagePreviewBody
import com.niki914.zafiro.app.ui.chat.composable.body.custom.RetryingBody
import com.niki914.zafiro.app.ui.chat.composable.body.custom.SkillBody
import com.niki914.zafiro.app.ui.chat.composable.body.custom.ThinkingBody
import com.niki914.zafiro.app.ui.chat.composable.body.standard.DetailedResultBody
import com.niki914.zafiro.app.ui.chat.composable.body.standard.SimpleStatusBody
import com.niki914.zafiro.app.ui.chat.model.ActionBodyUi

/**
 * Level 4: 动作内容纯分流器。
 *
 * 没有任何业务判断、JSON 反序列化或嵌套计算，
 * 纯粹作为类型安全的分流器，将每一个 case 委托到专门的 Composable。
 */
@Composable
internal fun ActionBodyDispatcher(
    ui: ActionBodyUi,
    modifier: Modifier = Modifier,
) {
    when (ui) {
        // ── 1. 定制样式 (Custom Style) ──
        is ActionBodyUi.Custom.Command      -> CodeToolBody(ui = ui, modifier = modifier)
        is ActionBodyUi.Custom.SkillContent -> SkillBody(ui = ui, modifier = modifier)
        is ActionBodyUi.Custom.ImagePreview -> ImagePreviewBody(ui = ui, modifier = modifier)
        is ActionBodyUi.Custom.Thinking     -> ThinkingBody(ui = ui, modifier = modifier)
        is ActionBodyUi.Custom.ErrorDetail  -> ErrorDetailBody(ui = ui, modifier = modifier)
        is ActionBodyUi.Custom.Retrying     -> RetryingBody(ui = ui, modifier = modifier)

        // ── 2. 通用样式 (Standard Style) ──
        is ActionBodyUi.Standard.Simple     -> SimpleStatusBody(ui = ui, modifier = modifier)
        is ActionBodyUi.Standard.Detailed   -> DetailedResultBody(ui = ui, modifier = modifier)
    }
}
