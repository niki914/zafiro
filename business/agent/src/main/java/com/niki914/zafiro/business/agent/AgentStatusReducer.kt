package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.LlmStreamEvent

private const val PREVIEW_MAX_CHARS = 120
private val WHITESPACE = Regex("\\s+")

/**
 * 状态归约辅助态：跟踪回合提问与模型生成的首句/最新文本，用于产出 preview。
 */
internal data class ReducedStatus(
    val status: AgentStatus = AgentStatus(),
    val roundQuery: String? = null,
    val firstAgentText: String? = null,
    val latestAgentText: String? = null,
)

/**
 * [AgentStatus] 的纯函数归约器。
 *
 * 无锁、无协程、不依赖引擎，根据输入事件驱动 [AgentStatus] 的阶段、结果与单行摘要流转。
 */
internal object AgentStatusReducer {

    fun startRound(query: String?): ReducedStatus {
        val normalizedQuery = singleLine(query)
        return ReducedStatus(
            status = AgentStatus(
                phase = AgentPhase.Generating,
                outcome = null,
                preview = normalizedQuery,
            ),
            roundQuery = normalizedQuery,
            firstAgentText = null,
            latestAgentText = null,
        )
    }

    fun reduce(current: ReducedStatus, event: LlmStreamEvent): ReducedStatus {
        return when (event) {
            LlmStreamEvent.RoundStarted -> {
                current.copy(
                    status = current.status.copy(
                        phase = AgentPhase.Generating,
                        outcome = null,
                    ),
                )
            }

            is LlmStreamEvent.TextDelta -> {
                val full = event.fullText
                val first = current.firstAgentText ?: singleLine(full)
                val latest = singleLine(full)
                val preview = first ?: current.roundQuery
                current.copy(
                    status = current.status.copy(
                        phase = AgentPhase.Generating,
                        outcome = null,
                        preview = preview,
                    ),
                    firstAgentText = first,
                    latestAgentText = latest,
                )
            }

            is LlmStreamEvent.ThinkingStarted,
            is LlmStreamEvent.ThinkingEnded,
            is LlmStreamEvent.Retrying -> {
                current.copy(
                    status = current.status.copy(
                        phase = AgentPhase.Generating,
                        outcome = null,
                    ),
                )
            }

            is LlmStreamEvent.ToolPending,
            is LlmStreamEvent.ToolRunning -> {
                current.copy(
                    status = current.status.copy(
                        phase = AgentPhase.ToolRunning,
                        outcome = null,
                    ),
                )
            }

            is LlmStreamEvent.ToolSucceeded,
            is LlmStreamEvent.ToolFailed -> {
                // 工具结算维持既有阶段，不改变状态
                current
            }

            LlmStreamEvent.Completed -> {
                current.copy(
                    status = current.status.copy(
                        phase = AgentPhase.Idle,
                        outcome = TurnOutcome.Completed,
                        preview = current.latestAgentText ?: current.firstAgentText,
                    ),
                )
            }

            is LlmStreamEvent.Error -> {
                current.copy(
                    status = current.status.copy(
                        phase = AgentPhase.Idle,
                        outcome = TurnOutcome.Failed,
                        preview = null,
                    ),
                )
            }
        }
    }

    fun interrupt(current: ReducedStatus): ReducedStatus {
        return current.copy(
            status = current.status.copy(
                phase = AgentPhase.Idle,
                outcome = TurnOutcome.Interrupted,
                preview = null,
            ),
        )
    }

    fun reset(): ReducedStatus = ReducedStatus(status = AgentStatus())

    internal fun singleLine(raw: String?): String? {
        val collapsed = raw?.replace(WHITESPACE, " ")?.trim().orEmpty()
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= PREVIEW_MAX_CHARS) return collapsed
        val end = if (Character.isHighSurrogate(collapsed[PREVIEW_MAX_CHARS - 1])) {
            PREVIEW_MAX_CHARS - 1
        } else {
            PREVIEW_MAX_CHARS
        }
        return collapsed.substring(0, end)
    }
}
