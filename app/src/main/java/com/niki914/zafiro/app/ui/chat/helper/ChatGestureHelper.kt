package com.niki914.zafiro.app.ui.chat.helper

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 专为包含 [androidx.compose.foundation.text.selection.SelectionContainer] 的卡片/文本设计的轻触修饰符。
 *
 * 核心设计：
 * 1. requireUnconsumed = false：不独占消费 Down 事件，底层 SelectionContainer 可正常监听长按起始；
 * 2. withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis)：在系统长按阈值（~400ms）内等待抬手，
 *    超时即视为长按划选，直接退出且抬手时不触发 onTap；
 * 3. !up.isConsumed：仅在抬手事件未被划词消费、未被内部复制按钮消费时，才判定为有效轻触打开查看器。
 */
internal fun Modifier.tapToOpenViewer(
    enabled: Boolean = true,
    onTap: () -> Unit,
): Modifier = if (!enabled) this else pointerInput(onTap) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            waitForUpOrCancellation()
        }
        if (up != null && !up.isConsumed) {
            up.consume()
            onTap()
        }
    }
}
