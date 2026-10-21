package com.niki914.zafiro.app.ui.chat.helper

import com.niki914.zafiro.app.ui.chat.composable.bubble.UserBubblePosition
import com.niki914.zafiro.app.ui.model.home.HomeChatTurn

/**
 * 渲染层连续 User 气泡分组：两个相邻 UserBubble 之间有无内容，取决于上一 turn 的 blocks
 * 是否为空（内容夹在上一气泡与下一气泡之间）。规则：
 * - 对下一条黏（自身成为组首/组中）：自身 blocks 为空——自身内容会显示在自身气泡与下一条之间；
 * - 对上一条黏（自身成为组末/组中）：上一 turn blocks 为空。
 * 因此流式内容到达只会改变"自身→下一条"一侧，组首一侧的黏性由上一 turn 决定，组不随内容跳变。
 */
internal fun userBubblePosition(turns: List<HomeChatTurn>, index: Int): UserBubblePosition {
    val current = turns.getOrNull(index)
    if (current == null || current.isCompactedDivider) return UserBubblePosition.Single
    val stickyUp = index > 0 && turns[index - 1].blocks.isEmpty() && !turns[index - 1].isCompactedDivider
    val stickyDown = index < turns.lastIndex && turns[index].blocks.isEmpty() && !turns[index + 1].isCompactedDivider
    return when {
        stickyUp && stickyDown -> UserBubblePosition.GroupMid
        stickyUp -> UserBubblePosition.GroupLast
        stickyDown -> UserBubblePosition.GroupFirst
        else -> UserBubblePosition.Single
    }
}

/**
 * 渲染层连续 User 气泡组（见 [userBubblePosition]）：index 所在组的组尾下标。
 * 组向下延伸的判定与 userBubblePosition 一致：自身 blocks 为空则下一气泡与本气泡相邻。
 */
internal fun userGroupTailIndex(turns: List<HomeChatTurn>, index: Int): Int {
    var tail = index
    while (tail < turns.lastIndex && turns[tail].blocks.isEmpty() && !turns[tail].isCompactedDivider && !turns[tail + 1].isCompactedDivider) tail++
    return tail
}

/** index 所在 User 组的组内全部用户文本：按序、双换行分隔、跳过空白（纯图片 turn）。 */
internal fun userGroupText(turns: List<HomeChatTurn>, index: Int): String {
    var head = index
    while (head > 0 && turns[head - 1].blocks.isEmpty() && !turns[head - 1].isCompactedDivider) head--
    val tail = userGroupTailIndex(turns, index)
    return turns.subList(head, tail + 1)
        .filterNot { it.isCompactedDivider }
        .map { it.userText }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
}
