package com.niki914.zafiro.api

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest

/**
 * 授权裁决者。由消费方实现并注册进 agent（见 [AgentControl.addApprover]），
 * agent 在需要确认时按注册顺序询问。
 *
 * 调用面：Compose 前台对话框、overlay 后台弹窗、常驻通知的允许 / 拒绝按钮。
 * 每个来源自己决定怎么呈现（对话框 / 弹窗 / 通知）与等多久。
 *
 * 本调用挂起直到来源给出裁决；挂起期间被取消表示该请求作废
 * （例如回合被停止），来源在自己的 `finally` 里撤销界面。
 * 今天的等价行为：`ToolPermissionCoordinator.confirm` 在工具执行路径里
 * 被等待，回合取消时等待被取消，对话框随 `pendingConfirmation` 清空而消失。
 * 永不超时（用户明确决策）。
 *
 * 返回 [ApprovalDecision.Abstain] 表示本次不由我处理，交给下一个来源。
 */
interface Approver {
    /**
     * 对一次确认请求给出裁决。
     *
     * @return 本次裁决；[ApprovalDecision.Abstain] 表示交给下一个来源。
     */
    suspend fun decide(request: ApprovalRequest): ApprovalDecision
}
