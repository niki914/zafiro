package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.model.AgentPhase
import com.niki914.zafiro.api.model.TurnOutcome
import com.niki914.zafiro.chat.AgentPhase as RuntimePhase
import com.niki914.zafiro.chat.AgentStatus as RuntimeStatus
import com.niki914.zafiro.chat.TurnOutcome as RuntimeOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentStatusMapperTest {

    @Test
    fun mapsRuntimePhaseOutcomeAndPreview() {
        assertEquals(
            com.niki914.zafiro.api.model.AgentStatus(
                phase = AgentPhase.WaitingApproval,
                outcome = TurnOutcome.Completed,
                preview = "done",
            ),
            RuntimeStatus(
                phase = RuntimePhase.WaitingPermission,
                preview = "done",
                outcome = RuntimeOutcome.Completed,
                permissionId = "internal-request-id",
            ).toApiStatus(),
        )
    }
}
