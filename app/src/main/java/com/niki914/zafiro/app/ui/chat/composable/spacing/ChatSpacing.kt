package com.niki914.zafiro.app.ui.chat.composable.spacing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.app.ui.content.BlockSpacing

/** 控制 Spacing 是否处于可视化 Debug 模式（黑底色块审查）。 */
val LocalChatSpacingDebug = compositionLocalOf { false }

// ── 聊天流垂直间距常量表（统一收敛于此处）───────────────────────────
internal val UserAttachmentToTextGap: Dp = 8.dp            // 附件行 ↔ 用户气泡 (8.dp，收紧图文一体感)
internal val ContinuousUserBubbleGap: Dp = 3.dp            // 连续用户气泡组内连体微间隙 (3.dp)
internal val UserBubbleToActionGap: Dp = 6.dp              // 用户气泡 ↔ 用户操作栏 (6.dp，紧密吸附主体)
internal val UserToAssistantGap: Dp = BlockSpacing         // 用户区 ↔ 助手生成流首块 (12.dp，角色转换标准间距)
internal val AssistantSectionGap: Dp = BlockSpacing        // 助手流内部段落卡片间距 (12.dp，模块呼吸间距)
internal val AssistantToActionGap: Dp = 6.dp               // 助手尾部内容 ↔ 助手操作栏 (6.dp，紧密吸附主体)
internal val TurnSeparatorGap: Dp = 24.dp                  // 跨轮次标准大分隔 (24.dp，收拢空隙提升信息密度)

@Composable
private fun ChatSpacingSlot(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val isDebug = LocalChatSpacingDebug.current
    if (!isDebug) {
        Spacer(
            modifier = modifier
                .fillMaxWidth()
                .height(height),
        )
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height)
                .background(Color.Black),
        )
    }
}

/** 附件行 ↔ 用户文本气泡间距 (8.dp) */
@Composable
fun UserAttachmentToTextSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = UserAttachmentToTextGap, modifier = modifier)
}

/** 连续用户气泡组内的连体微间隙 (3.dp) */
@Composable
fun ContinuousUserBubbleSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = ContinuousUserBubbleGap, modifier = modifier)
}

/** 用户气泡 ↔ 用户操作栏间距 (6.dp) */
@Composable
fun UserBubbleToActionSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = UserBubbleToActionGap, modifier = modifier)
}

/** 用户输入区（气泡或操作栏）↔ 助手生成流首块间距 (12.dp) */
@Composable
fun UserToAssistantSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = UserToAssistantGap, modifier = modifier)
}

/** 助手生成流内部各段落卡片（Thinking / Tool / Error / Text）之间的呼吸间距 (12.dp) */
@Composable
fun AssistantSectionSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = AssistantSectionGap, modifier = modifier)
}

/** 助手尾部内容（正文或卡片）↔ 助手操作栏间距 (6.dp) */
@Composable
fun AssistantToActionSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = AssistantToActionGap, modifier = modifier)
}

/** 跨轮次标准大分隔 (24.dp) */
@Composable
fun TurnSeparatorSpacing(modifier: Modifier = Modifier) {
    ChatSpacingSlot(height = TurnSeparatorGap, modifier = modifier)
}
