package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * 手势信任的贴底跟随状态机（外层聊天列表与内层 thinking/工具结果文本共用）。
 * 用户开始拖拽立即暂停跟随——流式新内容不抢占滚动；
 * 滚动完全停止后按 [isAtEnd] 判定是否恢复跟随。
 * 返回 MutableState：发送消息等场景可主动置回 true 恢复跟随。
 */
@Composable
internal fun rememberScrollFollowState(
    interactionSource: InteractionSource,
    isScrollInProgress: () -> Boolean,
    isAtEnd: () -> Boolean,
): MutableState<Boolean> {
    val followState = rememberSaveable { mutableStateOf(true) }
    var hasPendingUserScrollDecision by remember { mutableStateOf(false) }
    val isUserDragging by interactionSource.collectIsDraggedAsState()

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            followState.value = false
            hasPendingUserScrollDecision = true
        }
    }
    LaunchedEffect(interactionSource) {
        snapshotFlow { isScrollInProgress() }
            .collectLatest { inProgress ->
                if (!inProgress && hasPendingUserScrollDecision) {
                    followState.value = isAtEnd()
                    hasPendingUserScrollDecision = false
                }
            }
    }
    return remember {
        object : MutableState<Boolean> {
            override var value: Boolean
                get() = followState.value
                set(next) {
                    followState.value = next
                    hasPendingUserScrollDecision = false
                }

            override fun component1(): Boolean = followState.value
            override fun component2(): (Boolean) -> Unit = { value = it }
        }
    }
}
