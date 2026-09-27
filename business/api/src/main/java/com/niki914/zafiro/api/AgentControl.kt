package com.niki914.zafiro.api

import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统入口（常驻通知、悬浮窗、MainActivity）的最小能力：读状态 + 停止 + 参与授权。
 *
 * 调用面：
 * - 常驻通知（待建）：订阅 [status] 渲染标题与正文，取消按钮调 [stop]，
 *   允许 / 拒绝按钮注册为 [Approver]。
 * - 悬浮窗（待建）：订阅 [status]，弹窗注册为 [Approver]。
 * - MainActivity：屏幕常亮 = `status.value.isRunning`（今天读自有中介接口的
 *   `keepScreenOn`，接入后改为订阅本字段）。
 *
 * 宽接口 [Agent] 继承本接口：Compose、宿主取 [Agent] 即可，
 * 需要窄能力的客户端取本接口，`stream()` / `updateDraft()` /
 * `load()` 在编译期对它们不可见。
 */
interface AgentControl {

    /**
     * 粗粒度状态：类型自身携带该阶段的内生数据。晚订阅立即取得当前值。
     *
     * 实现保证回合终态时先发布 [Agent.conversation] 的最终内容，
     * 再发布本字段的 [AgentState.Idle]（宿主渲染桥的收尾依赖这个顺序，否则会在
     * 最后一帧内容到达前就停止）。
     */
    val status: StateFlow<AgentState>

    /**
     * 请求停止当前回合。无活跃回合时为空操作。
     *
     * 调用面：
     * - Compose 停止按钮（今天是 `StopGenerating` 意图 → `stopCurrentRound()`）；
     * - 常驻通知的取消按钮（待建）；
     * - 宿主 cancel（今天是 `AgentRuntimeService.cancel` 经 Binder）。
     *
     * 返回时机是停止请求已受理，不等待资源清理完成。回合级工具资源
     * （Python 进程、终端会话）由实现内部在引擎停止钩子里清理
     * （今天是 `LLMController` 的 `killToolResourcesHook`：
     * `PyRuntime.kill()` + `TerminalSessionPool.closeAll()`），
     * 与哪个客户端调用无关。回合结束经 [status] 观察
     * （`Idle`，`lastOutcome` = Interrupted）。
     */
    fun stop()

    /**
     * 注册授权裁决者。同一实例重复注册为幂等。
     *
     * 调用面：
     * - Compose 前台对话框（注册 [Approver]，界面不可见时不注册或返回弃权）；
     * - overlay 悬浮球卡片；
     * - 常驻通知的允许 / 拒绝按钮。
     *
     * 裁决并发询问所有已注册来源，先到先得：第一个返回非 [ApprovalDecision.Abstain]
     * 的来源结算，注册顺序不代表优先级。结算后其余来源的挂起调用被取消，
     * 各自在取消回调里撤下自己的界面——同一次请求只在一个地方决策。
     * 没有任何来源注册，或全部弃权时视为拒绝。
     */
    fun addApprover(approver: Approver)

    /**
     * 注销授权裁决者（按实例身份）。未注册时为空操作。
     *
     * 注册方负责注销（Compose 在 `DisposableEffect` 里，通知在服务创建 /
     * 销毁时），否则界面销毁后会留下仍在响应请求的僵尸来源。
     */
    fun removeApprover(approver: Approver)

    /**
     * 发起一次审批裁决。并发询问已注册的 [Approver]，首个非 [ApprovalDecision.Abstain]
     * 决策胜出；若无已注册的裁决者或全部弃权则返回 [ApprovalDecision.Deny]。
     */
    suspend fun decideApproval(request: ApprovalRequest): ApprovalDecision
}
