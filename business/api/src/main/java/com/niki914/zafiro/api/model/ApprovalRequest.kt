package com.niki914.zafiro.api.model

/**
 * 一次工具执行确认请求。
 *
 * 消费方：
 * - 常驻通知的允许 / 拒绝按钮（待建，注册为 `Approver` 后直接返回裁决）；
 * - Compose 前台对话框、overlay 后台弹窗（`[toolName]` / `[command]` / `[ruleName]`
 *   作标题、正文、副标题）。
 *
 * 本类型只描述「问什么」：结算靠 [Approver.decide] 的返回值，
 * 因此不需要请求 id（今天 `ToolPermissionCoordinator.respond(id)` 需要 id
 * 定位等待器，新机制下等待器就是这次挂起调用本身）。
 * 多个工具可同时等待确认，每次询问是独立的挂起调用，互不干扰。
 */
data class ApprovalRequest(
    /** 工具展示名。消费方：前台对话框、弹窗标题、通知正文。 */
    val toolName: String,

    /** 待确认的命令原文。消费方：前台对话框、弹窗正文、通知展开后的详情。 */
    val command: String,

    /** 命中的执行规则名。消费方：前台对话框、弹窗副标题。 */
    val ruleName: String,
)

/**
 * 授权裁决者的回答。
 *
 * [Abstain] 是多来源机制的关键：来源按优先级被询问，
 * 第一个非弃权者结算。现有实现把「前台优先、否则后台弹窗、否则拒绝」
 * 写死在协调器里，新增通知按钮后需要显式化，否则多个来源可能同时裁决
 * 同一次请求。
 */
enum class ApprovalDecision {
    Allow,
    Deny,

    /** 本来源不处理这次请求，交给下一个。 */
    Abstain,
}
