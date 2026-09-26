package com.niki914.zafiro.app.permission

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.business.agent.AgentImpl
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse

/**
 * 临时桥接：将遗留 [com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator] 的待确认请求转发至新架构 [AgentImpl] 的 [com.niki914.zafiro.api.Approver] 体系。
 * 待全面迁移至 Approver 后下线。
 */
object ToolPermissionCoordinatorApproverImpl__Tmp {

    suspend fun confirm(request: ToolPermissionRequest): ToolPermissionResponse {
        if (!AgentImpl.hasApprovers) {
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        val approvalRequest = ApprovalRequest(
            toolName = request.toolName,
            command = request.command,
            ruleName = request.matchedRuleName,
        )
        return when (AgentImpl.decideApproval(approvalRequest)) {
            ApprovalDecision.Allow -> ToolPermissionResponse.ALLOWED
            ApprovalDecision.Deny -> ToolPermissionResponse.DENIED_BY_USER
            ApprovalDecision.Abstain -> ToolPermissionResponse.DENIED_UNAVAILABLE
        }
    }
}
