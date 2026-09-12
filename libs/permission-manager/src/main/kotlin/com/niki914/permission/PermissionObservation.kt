package com.niki914.permission

/**
 * 权限引擎只读观察契约（T-01，AC6 契约字段）。
 *
 * 由 `PermissionEngine` 在请求、渠道开始、各渠道尝试、handler 异常、结果/取消处同步回调。
 * 约束（design §9.8–9.9）：
 * - 同步轻量：回调内只记录事实，不做 IO、不挂起、不申请权限。
 * - 不抛出：观察者抛异常不得影响引擎链顺序与返回值（引擎侧隔离）。
 * - 无业务依赖：仅引用本模块的 [Permission]/[Channel]/[Attempt]/[PermissionResult]，
 *   不依赖 agent-runtime 或任何 UI 类型。
 * - 观察不能改变决定：事件只读，不能把系统权限直接改为已授权。
 *
 * 身份：[requestId] 由引擎每次 `request` 调用分配，同一调用的全部事件共享；
 * 观察者按 requestId 归属事实，不读取任何全局“当前请求”。
 * 生产者必须传入不可变快照（`toList()` 复制），不得暴露内部可变列表。
 */
interface PermissionObservation {
    fun onEvent(event: PermissionObservationEvent)
}

/** 库侧原始授权事实：仅请求 ID、permission、channel、阶段、结果。 */
sealed interface PermissionObservationEvent {
    val requestId: String

    /** 引擎开始一次请求，按传入链顺序逐渠道尝试。 */
    data class RequestStarted(
        override val requestId: String,
        val permission: Permission,
        val channels: List<Channel>,
    ) : PermissionObservationEvent

    /** 进入单个渠道等待前：在 suspend 调用 handler 之前同步发出，in-flight 渠道可见
     *（reducer 据此置 Processing；慢渠道挂起期间快照不保持“无进展”假象）。 */
    data class ChannelStarted(
        override val requestId: String,
        val permission: Permission,
        val channel: Channel,
    ) : PermissionObservationEvent

    /**
     * 渠道 handler 抛非取消异常：携带原始异常对象（[Attempt] 只有 detail 字符串，
     * 不足以排障）。引擎原行为不变：该异常映射为 FAILED 继续下一环；
     * `CancellationException` 仍走 [RequestCancelled] 并继续向上传播。
     */
    data class HandlerThrew(
        override val requestId: String,
        val permission: Permission,
        val channel: Channel,
        val error: Throwable,
    ) : PermissionObservationEvent

    /** 单个渠道尝试完成（含 handler 缺失、版本门槛未达等未实际请求的情况）。 */
    data class ChannelAttempted(
        override val requestId: String,
        val attempt: Attempt,
    ) : PermissionObservationEvent

    /** 请求结算：原 [PermissionResult] 整体透传，引擎返回值与异常语义不变。 */
    data class RequestFinished(
        override val requestId: String,
        val result: PermissionResult,
    ) : PermissionObservationEvent

    /** 请求被取消（`CancellationException` 继续向上传播，观察只记录）。 */
    data class RequestCancelled(
        override val requestId: String,
        val permission: Permission,
    ) : PermissionObservationEvent
}
