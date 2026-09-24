package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.AgentStatus
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.AgentStatus as RuntimeAgentStatus
import com.niki914.zafiro.chat.AgentPhase as RuntimeAgentPhase
import com.niki914.zafiro.chat.TurnOutcome as RuntimeTurnOutcome

/** Runtime status → public contract status. Permission request ids stay internal to the approver flow. */
internal fun RuntimeAgentStatus.toApiStatus(): AgentStatus = AgentStatus(
    phase = when (phase) {
        RuntimeAgentPhase.Idle -> AgentPhase.Idle
        RuntimeAgentPhase.Generating -> AgentPhase.Generating
        RuntimeAgentPhase.ToolRunning -> AgentPhase.ToolRunning
        RuntimeAgentPhase.WaitingPermission -> AgentPhase.WaitingApproval
    },
    outcome = when (outcome) {
        null -> null
        RuntimeTurnOutcome.Completed -> TurnOutcome.Completed
        RuntimeTurnOutcome.Failed -> TurnOutcome.Failed
        RuntimeTurnOutcome.Interrupted -> TurnOutcome.Interrupted
    },
    preview = preview,
)
