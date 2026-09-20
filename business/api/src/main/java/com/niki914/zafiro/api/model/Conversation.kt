package com.niki914.zafiro.api.model

/**
 * 会话身份。今天 `HomeChatState` 自持 `currentConversationId: String?`，
 * 接入后以本字段为准。
 *
 * 与 okia 的会话树 id、Room 会话 id 对齐（现有语义：三者相等）。
 */
@JvmInline
value class ConversationId(val value: String)

/**
 * 当前会话的内容：会话身份 + 回合列表（含正在流式产生的那一轮）。
 *
 * 身份与内容在同一次发射里，读者不需要从两个地方拼「现在是哪个会话」
 * 与「它有哪些回合」。`id` 为 null = 尚未建档（首次 `stream()` 时由实现创建）。
 *
 * ## 设计边界：本类型不承载落盘
 *
 * 会话内容的落盘继续由 `ConversationPersister` 观察引擎的会话树完成，
 * Room 的 schema 与存量数据保持不变（有存量用户）。契约与落盘之间因此没有耦合，
 * 本类型的字段按「有生产消费方」取舍，不为落盘预留。落盘口径将来真要改成本类型时，
 * 需要补的是提交边界，那时再加。
 *
 * 有意去掉的字段：
 * - `isComplete`：它唯一的用途是让落盘跳过正在流式产生的那一轮。本轮落盘不读本类型；
 *   Compose 的滚动跟随由 [TurnBlock.Thinking.isComplete] 判断，操作行可用性由
 *   `AgentStatus.phase` 与「是否最后一个回合」派生。
 */
data class Conversation(
    val id: ConversationId? = null,
    val turns: List<ConversationTurn> = emptyList(),
)

/**
 * 回合身份。实现侧生成，用字符串而非自增序号。
 * 现有实现没有回合身份（`HomeChatViewModel` 自己维护一个 `Long`
 * 计数器当 UI key）；进入状态模型后，消费方用它定位「消息操作行」
 * （复制 / 重新生成 / 派生）。
 *
 * 落盘层不存这个 id（见本文件的边界说明），要求实现侧在重新装配
 * （含历史恢复）时重新推导出稳定值。
 */
@JvmInline
value class TurnId(val value: String)

/**
 * 一个回合的业务模型：用户输入 + agent 产出的块序列。
 *
 * 这是流式构建与历史恢复的合并点：现在两条独立装配路径
 * （`HomeChatViewModel.applyEvent` 与 `ConversationFormatter.toHomeTurns`）
 * 各自把消息映射成 UI 块，字段含义靠人工对齐。真源只有这一份状态后，
 * 两条路径都产出它。
 */
data class ConversationTurn(
    val id: TurnId,
    val userText: String,
    val attachments: List<Attachment> = emptyList(),
    val blocks: List<TurnBlock> = emptyList(),
)
