package com.niki914.zafiro.api.model

/**
 * 一次执行或知情确认请求。
 *
 * 消费方：
 * - 常驻通知的允许 / 拒绝按钮（注册为 `Approver` 后直接返回裁决）；
 * - Compose 前台对话框、overlay 悬浮球卡片（根据不同子类型展示标题、正文与规则）。
 *
 * 本类型只描述「问什么」：结算靠 [com.niki914.zafiro.api.Approver.decide] 的返回值。
 * 多个来源并发询问，首个非 Abstain 决策胜出。
 */
sealed interface ApprovalRequest {

    /**
     * 高危工具执行确认（终端执行 rm -rf 等匹配到 CONFIRM 规则）。
     */
    data class ToolExecution(
        /** 工具展示名。消费方：前台对话框、弹窗标题、通知正文。 */
        val toolName: String,

        /** 待确认的命令原文。消费方：前台对话框、弹窗正文、通知展开后的详情。 */
        val command: String,

        /** 命中的执行规则名。消费方：前台对话框、弹窗副标题。 */
        val ruleName: String,
    ) : ApprovalRequest

    /**
     * 屏幕控制知情同意（调用需要无障碍或悬浮窗能力的工具前置知情门）。
     */
    data object ScreenControlConsent : ApprovalRequest
}
