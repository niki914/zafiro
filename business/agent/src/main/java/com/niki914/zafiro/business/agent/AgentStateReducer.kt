package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.LlmStreamEvent

/** state 存 raw 前 500 字；单行化与行数截断下沉到各消费方。 */
private const val TEXT_MAX_CHARS = 500

/**
 * 状态归约辅助态：跟踪当段文本。`Generating(text)` 为 null 即未开口；
 * `Idle.lastText` 取自终态时刻的 `segmentText`（没开过口即 null）。
 */
internal data class ReducedStatus(
    val status: AgentState = AgentState.Idle(),
    val segmentText: String? = null,
)

/**
 * [AgentState] 的纯函数归约器。
 *
 * 无锁、无协程、不依赖引擎，根据输入事件驱动 [AgentState] 流转。
 */
internal object AgentStateReducer {

    fun startRound(): ReducedStatus {
        return ReducedStatus(
            status = AgentState.Generating(text = null),
            segmentText = null,
        )
    }

    fun stopping(current: ReducedStatus): ReducedStatus {
        return current.copy(status = AgentState.Stopping)
    }

    fun reduce(current: ReducedStatus, event: LlmStreamEvent): ReducedStatus {
        if (current.status is AgentState.Stopping) {
            return when (event) {
                LlmStreamEvent.Completed -> {
                    current.copy(
                        status = AgentState.Idle(
                            lastOutcome = TurnOutcome.Completed,
                            lastText = current.segmentText,
                        ),
                    )
                }
                is LlmStreamEvent.Error -> {
                    current.copy(
                        status = AgentState.Idle(
                            lastOutcome = TurnOutcome.Failed,
                            lastText = current.segmentText,
                        ),
                    )
                }
                else -> current
            }
        }
        return when (event) {
            LlmStreamEvent.RoundStarted -> {
                current.copy(
                    status = AgentState.Generating(text = null),
                    segmentText = null,
                )
            }

            is LlmStreamEvent.TextDelta -> {
                // fullText 是段内全量（新段 isSegmentStart 即重置）；异常流回退用事件全量
                val text = event.fullText.takeHead()
                current.copy(
                    status = AgentState.Generating(text = text),
                    segmentText = text,
                )
            }

            is LlmStreamEvent.ThinkingStarted -> {
                val text = event.text.takeHead()
                current.copy(status = AgentState.Thinking(text = text))
            }

            is LlmStreamEvent.ThinkingEnded,
            is LlmStreamEvent.Retrying -> {
                current.copy(
                    status = AgentState.Generating(text = current.segmentText),
                )
            }

            is LlmStreamEvent.ToolPending -> {
                current.copy(
                    status = AgentState.ToolRunning(
                        toolName = event.call.name,
                        label = event.call.label,
                        argumentsJson = event.call.argumentsJson,
                    ),
                )
            }
            is LlmStreamEvent.ToolRunning -> {
                current.copy(
                    status = AgentState.ToolRunning(
                        toolName = event.call.name,
                        label = event.call.label,
                        argumentsJson = event.call.argumentsJson,
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
                    status = AgentState.Idle(
                        lastOutcome = TurnOutcome.Completed,
                        lastText = current.segmentText,
                    ),
                )
            }

            is LlmStreamEvent.Error -> {
                current.copy(
                    status = AgentState.Idle(
                        lastOutcome = TurnOutcome.Failed,
                        lastText = current.segmentText,
                    ),
                )
            }
        }
    }

    fun interrupt(current: ReducedStatus): ReducedStatus {
        return current.copy(
            status = AgentState.Idle(
                lastOutcome = TurnOutcome.Interrupted,
                lastText = current.segmentText,
            ),
        )
    }

    fun reset(): ReducedStatus = ReducedStatus(status = AgentState.Idle())

    internal fun String.takeHead(): String? {
        val collapsed = replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        if (collapsed.length <= TEXT_MAX_CHARS) return collapsed
        val end = if (Character.isHighSurrogate(collapsed[TEXT_MAX_CHARS - 1])) {
            TEXT_MAX_CHARS - 1
        } else {
            TEXT_MAX_CHARS
        }
        return collapsed.substring(0, end)
    }

    private val WHITESPACE = Regex("\\s+")
}
