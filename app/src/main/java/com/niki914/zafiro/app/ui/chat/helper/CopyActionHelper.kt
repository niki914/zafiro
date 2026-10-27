package com.niki914.zafiro.app.ui.chat.helper

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import kotlinx.coroutines.launch

internal val CopyBtnSize = 24.dp
internal val CopyBtnGap = 4.dp
internal val OutputBtnInset = 32.dp


/**
 * 诚实复制文本到剪贴板并给出触觉与 Toast 反馈：
 * - [text] 为 null 或 [String.isBlank] 时严格拦截，不产生任何副作用；
 * - 只有写入剪贴板无异常时才算成功，触发触感震动与即时 Toast 反馈；
 * - 异常时返回 false，绝不虚假上报“已复制”。
 */
internal suspend fun honestCopyToClipboard(
    text: String?,
    clipboard: Clipboard,
    hapticFeedback: HapticFeedback? = null,
    @StringRes toastRes: Int = R.string.ui_toast_copied,
): Boolean {
    if (text.isNullOrBlank()) return false
    return runCatchingCancellable {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
        hapticFeedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        ZafiroToast.show(toastRes)
        true
    }.getOrDefault(false)
}

/**
 * 小号复制按钮（比标准 IconButton 小、无背景直接融入），点击写入剪贴板。
 * 无水波纹（依赖震动与 Toast 即时反馈）。
 */
@Composable
internal fun MiniCopyButton(
    text: String?,
    modifier: Modifier = Modifier,
) {
    if (text.isNullOrBlank()) return
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .size(CopyBtnSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                scope.launch {
                    honestCopyToClipboard(text = text, clipboard = clipboard, hapticFeedback = haptic)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}
