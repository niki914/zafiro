package com.niki914.zafiro.app.notification

import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ResidentNotificationManagerTest {

    @Before
    @After
    fun cleanUp() {
        ResidentNotificationManager.resetForTest()
    }

    @Test
    fun decide_suspendsUntilResolveApprovalAllow() = runTest {
        val request = ApprovalRequest(toolName = "bash", command = "ls -la", ruleName = "shell_rule")
        val deferred = async {
            ResidentNotificationManager.residentApprover.decide(request)
        }
        testScheduler.runCurrent()

        assertSame(request, ResidentNotificationManager.activeApprovalRequest)

        ResidentNotificationManager.resolveApproval(ApprovalDecision.Allow)
        testScheduler.runCurrent()

        val decision = deferred.await()
        assertEquals(ApprovalDecision.Allow, decision)
        assertNull(ResidentNotificationManager.activeApprovalRequest)
    }

    @Test
    fun decide_suspendsUntilResolveApprovalDeny() = runTest {
        val request = ApprovalRequest(toolName = "bash", command = "rm -rf /", ruleName = "dangerous_rule")
        val deferred = async {
            ResidentNotificationManager.residentApprover.decide(request)
        }
        testScheduler.runCurrent()

        assertSame(request, ResidentNotificationManager.activeApprovalRequest)

        ResidentNotificationManager.resolveApproval(ApprovalDecision.Deny)
        testScheduler.runCurrent()

        val decision = deferred.await()
        assertEquals(ApprovalDecision.Deny, decision)
        assertNull(ResidentNotificationManager.activeApprovalRequest)
    }

    @Test
    fun decide_cleansUpOnCancellation() = runTest {
        val request = ApprovalRequest(toolName = "bash", command = "top", ruleName = "monitor")
        val job = async {
            ResidentNotificationManager.residentApprover.decide(request)
        }
        testScheduler.runCurrent()

        assertSame(request, ResidentNotificationManager.activeApprovalRequest)

        job.cancelAndJoin()
        assertNull(ResidentNotificationManager.activeApprovalRequest)
    }
}
