package com.niki914.zafiro.api.model

/**
 * Agent 的粗粒度状态：类型自身携带该阶段的内生数据。系统入口的读模型
 * （常驻通知、悬浮窗、MainActivity 的屏幕常亮）。
 *
 * 约定：
 * - 文本一律是当前段的最新全文（Msg 级，raw 前 500 字），`null` = 尚未开口；
 *   单行化与截断下沉到各消费方，不在本层做。
 * - `outcome` 只出现在 [Idle]（`lastOutcome`）；进行中各态不带结束信息。
 * - [Idle.lastText] 是末轮最后开口文本的投影缓存（与 `conversation` 真源有意冗余，
 *   免得通知栏/悬浮窗为了一句尾巴去 combine 整棵会话树）；null = 本轮没开过口。
 * - `isRunning = this !is Idle`，各消费方直接用，不再各自发明派生。
 */
sealed interface AgentState {

    /**
     * 待命。`lastOutcome == null` = 本进程尚未发起过回合（新鲜态）。
     * `lastText` = 末轮最后开口文本（head-500，已单行化过）；null = 本轮没开过口。
     */
    data class Idle(
        val lastOutcome: TurnOutcome? = null,
        val lastText: String? = null,
    ) : AgentState

    /** 正在输出正文。`text == null` = 回合已发起、模型尚未开口。 */
    data class Generating(val text: String?) : AgentState

    /** 思考块进行中（CoT / 思维链模式），尚未开始输出正式回答。 */
    data class Thinking(val text: String?) : AgentState

    /** 工具执行中。UI 用 [label] 派生展示名，详情卡可用 [argumentsJson]。 */
    data class ToolRunning(
        val toolName: String,
        val label: String,
        val argumentsJson: String?,
    ) : AgentState

    /** 等待工具执行确认。UI 从 [request] 自行派生展示文案。 */
    data class WaitingApproval(val request: ApprovalRequest) : AgentState

    /** 正在停止中：底层资源正在清理与取消，尚未完全进入 [Idle]。不带文本。 */
    data object Stopping : AgentState
}

/** 回合的结束方式。 */
enum class TurnOutcome {
    Completed,
    Failed,

    /** 用户打断，或回合尾没有终态事件。 */
    Interrupted,
}

/** 除 [AgentState.Idle] 外都算运行中。 */
val AgentState.isRunning: Boolean
    get() = this !is AgentState.Idle
