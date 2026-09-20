package com.niki914.zafiro.api

import com.niki914.zafiro.api.model.AgentStatus
import kotlinx.coroutines.flow.StateFlow

/**
 * 系统入口（常驻通知、悬浮窗、MainActivity）的最小能力：读状态 + 停止 + 参与授权。
 *
 * 调用面：
 * - 常驻通知（待建）：订阅 [status] 渲染标题与正文，取消按钮调 [stop]，
 *   允许 / 拒绝按钮注册为 [Approver]。
 * - 悬浮窗（待建）：订阅 [status]，弹窗注册为 [Approver]。
 * - MainActivity：屏幕常亮 = `status.phase != Idle`（今天读自有中介接口的
 *   `keepScreenOn`，接入后改为订阅本字段）。
 *
 * 宽接口 [Agent] 继承本接口：Compose、宿主取 [Agent] 即可，
 * 需要窄能力的客户端取本接口，`stream()` / `updateDraft()` /
 * `load()` 在编译期对它们不可见。
 */
interface AgentControl {

    /**
     * 粗粒度状态：阶段 + 结束方式 + 单行摘要。晚订阅立即取得当前值。
     *
     * 实现保证回合终态时先发布 [Agent.conversation] 的最终内容，
     * 再发布本字段的 Idle（宿主渲染桥的收尾依赖这个顺序，否则会在
     * 最后一帧内容到达前就停止）。
     */
    val status: StateFlow<AgentStatus>

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
     * （`phase` → Idle，`outcome` = Interrupted）。
     */
    fun stop()

    /**
     * 注册授权裁决者。同一实例重复注册为幂等；顺序即优先级。
     *
     * 调用面（今天三处各走各的，接入后统一为注册）：
     * - Compose 前台对话框（今天读 `ToolPermissionCoordinator.pendingConfirmation`、
     *   经 `respond(id)` 结算，接入后改为注册 [Approver] 并直接返回裁决）；
     * - overlay 后台弹窗（今天是 `App.backgroundConfirmationHandler` +
     *   `ToolPermissionOverlay.show`，接入后改为注册 [Approver]）；
     * - 常驻通知的允许 / 拒绝按钮（待建，注册 [Approver]）。
     *
     * 按注册顺序询问，第一个返回非弃权者结算；全部弃权或没有任何来源
     * 注册时视为拒绝（今天的 `DENIED_UNAVAILABLE`）。今天的硬编码路由
     * （前台优先 `isUiResumed`、否则后台处理器、否则拒绝）由注册顺序替代：
     * 前台的 [Approver] 在界面不可见时返回弃权即可。
     */
    fun addApprover(approver: Approver)

    /**
     * 注销授权裁决者（按实例身份）。未注册时为空操作。
     *
     * 注册方负责注销（Compose 在 `DisposableEffect` 里，通知在服务创建 /
     * 销毁时），否则界面销毁后会留下仍在响应请求的僵尸来源。
     */
    fun removeApprover(approver: Approver)
}
