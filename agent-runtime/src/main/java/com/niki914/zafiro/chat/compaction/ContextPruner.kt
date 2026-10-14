package com.niki914.zafiro.chat.compaction

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.zafiro.api.text.SummaryBlock

/**
 * 会话上下文纯逻辑裁剪器。
 *
 * 输入平坦消息列表，定位最新的 [<zfr-summary>] 压缩检查点，
 * 倒数 [SummaryBlock.Payload.keepTurns] 轮用户消息确定保留区边界，
 * 组装出结构清晰、工具链闭合的紧凑发送态消息切片。
 */
object ContextPruner {

    /**
     * 上下文裁剪输出结果。
     *
     * @property messages 裁剪后的消息列表（若触发压缩，首项为摘要检查点，随后是保留区与新消息）。
     * @property hasCompacted 是否匹配到压缩检查点并执行了裁剪/重组。
     * @property prunedCount 丢弃的旧历史消息数量。
     */
    data class PruneResult(
        val messages: List<Message>,
        val hasCompacted: Boolean,
        val prunedCount: Int,
    )

    /**
     * 纯函数：根据历史中最新的压缩检查点进行上下文裁剪。
     *
     * - 若不存在压缩检查点：原样返回 [history]，[hasCompacted] 为 false。
     * - 若存在检查点：
     *   1. 提取其 [SummaryBlock.Payload.keepTurns]；
     *   2. 逆向倒序扫描匹配 [keepTurns] 轮前置 [Message.User]（严禁跨越更早的旧压缩点）；
     *   3. 组装：`[检查点摘要消息] + [保留区消息] + [检查点之后的新消息]`。
     */
    fun prune(history: List<Message>): PruneResult {
        if (history.isEmpty()) {
            return PruneResult(messages = history, hasCompacted = false, prunedCount = 0)
        }

        var summaryIdx = -1
        var summaryPayload: SummaryBlock.Payload? = null

        for (i in history.indices.reversed()) {
            val msg = history[i]
            if (msg is Message.User) {
                val payload = summaryPayloadOf(msg)
                if (payload != null) {
                    summaryIdx = i
                    summaryPayload = payload
                    break
                }
            }
        }

        if (summaryIdx == -1 || summaryPayload == null) {
            return PruneResult(messages = history, hasCompacted = false, prunedCount = 0)
        }

        val keepTurns = summaryPayload.keepTurns
        var keepStartIndex = summaryIdx

        if (keepTurns > 0) {
            var userCount = 0
            for (i in summaryIdx - 1 downTo 0) {
                val msg = history[i]
                if (msg is Message.User) {
                    if (summaryPayloadOf(msg) != null) {
                        // 遇到更早的旧 summary 检查点，严禁跨越
                        keepStartIndex = i + 1
                        break
                    }
                    userCount++
                    keepStartIndex = i
                    if (userCount == keepTurns) {
                        break
                    }
                }
            }
        }

        val summaryMessage = history[summaryIdx]
        val keptMessages = history.subList(keepStartIndex, summaryIdx)
        val subsequentMessages = history.subList(summaryIdx + 1, history.size)

        val pruned = ArrayList<Message>(1 + keptMessages.size + subsequentMessages.size).apply {
            add(summaryMessage)
            addAll(keptMessages)
            addAll(subsequentMessages)
        }

        return PruneResult(
            messages = pruned,
            hasCompacted = true,
            prunedCount = keepStartIndex,
        )
    }

    private fun summaryPayloadOf(message: Message.User): SummaryBlock.Payload? {
        val text = message.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }
        return SummaryBlock.strip(text).payload
    }
}
