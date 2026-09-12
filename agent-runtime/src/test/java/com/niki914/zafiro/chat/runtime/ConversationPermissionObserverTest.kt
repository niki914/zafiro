package com.niki914.zafiro.chat.runtime

import com.niki914.permission.Attempt
import com.niki914.permission.Channel
import com.niki914.permission.Permission
import com.niki914.permission.PermissionObservationEvent
import com.niki914.permission.PermissionResult
import com.niki914.permission.PermissionState
import com.niki914.zafiro.chat.agentic.accessibility.ScreenControlConsent
import com.niki914.zafiro.chat.agentic.shell.TOOL_CONFIRM_CHANNEL_BACKGROUND
import com.niki914.zafiro.chat.agentic.shell.TOOL_CONFIRM_CHANNEL_FOREGROUND
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Group9：授权观察与响应转发测试（T-21，AC6/AC9）。
 *
 * 驱动真实 [ConversationPermissionObserver]，并走真实前台工具确认
 * （[ToolPermissionCoordinator]）、真实屏控同意（[ScreenControlConsent]）与真实
 * [PermissionObservationEvent] 系统链。身份只来自 [AuthorizationContext]；
 * 无证据的 toolCallId 保持 unknown。时序由 StandardTestDispatcher 控制，无 sleep。
 * 本文件只写测试，不执行（终审统一跑 gradle）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationPermissionObserverTest {

    // ── fixtures ───────────────────────────────────────────────────────────

    /** 记录型下游：全部事实按序保存，可按授权事实过滤。 */
    private class RecordingSink : RuntimeFactSink {
        val facts = mutableListOf<RuntimeFact>()
        override fun accept(fact: RuntimeFact) {
            facts += fact
        }

        val permissions: List<PermissionRequestObservation>
            get() = facts.filterIsInstance<RuntimeFact.PermissionReported>().map { it.observation }
    }

    @Before
    fun resetToolConfirmationChannel() {
        ToolPermissionCoordinator.isUiResumed = false
        ToolPermissionCoordinator.backgroundConfirmationHandler = null
    }

    @After
    fun clearToolConfirmationChannel() {
        ToolPermissionCoordinator.isUiResumed = false
        ToolPermissionCoordinator.backgroundConfirmationHandler = null
    }

    private fun turn(id: String, epoch: Long = 1L) = TurnKey("conv", id, epoch)

    private fun request(id: String = "call-1") =
        ToolPermissionRequest(id, "shell", "ls", "rule")

    private fun observation(
        requestId: String,
        turn: TurnKey,
        phase: PermissionPhase,
        channel: String? = null,
        toolCallId: String? = null,
        kind: PermissionKind = PermissionKind.ToolConfirmation,
        reason: Reason? = null,
    ) = PermissionRequestObservation(
        requestId = requestId,
        turn = turn,
        toolCallId = toolCallId,
        kind = kind,
        target = "shell",
        channel = channel,
        phase = phase,
        reason = reason,
    )

    // ── 前台工具确认：真实等待器 + 精确身份 ────────────────────────────────

    @Test
    fun foregroundConfirmationBindsTurnRequestAndToolCallIdThenCompletesRealWaiter() = runTest {
        ToolPermissionCoordinator.isUiResumed = true
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val auth = observer.contextFor(turn).copy(toolCallId = "call-7")

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request()) } }
        runCurrent()

        assertEquals(2, sink.permissions.size)
        val requested = sink.permissions[0]
        assertEquals("call-1", requested.requestId)
        assertEquals(turn, requested.turn)
        assertEquals("call-7", requested.toolCallId)
        assertEquals(PermissionKind.ToolConfirmation, requested.kind)
        assertEquals(PermissionPhase.Requested, requested.phase)
        assertNull(requested.channel)
        val processing = sink.permissions[1]
        assertEquals(PermissionPhase.Processing, processing.phase)
        assertEquals(TOOL_CONFIRM_CHANNEL_FOREGROUND, processing.channel)

        assertEquals(CommandOutcome.Applied, observer.respond(turn, "call-1", true))
        runCurrent()
        assertEquals(ToolPermissionResponse.ALLOWED, confirm.await())
        val allowed = sink.permissions.last()
        assertEquals(PermissionPhase.Allowed, allowed.phase)
        assertEquals(TOOL_CONFIRM_CHANNEL_FOREGROUND, allowed.channel)
        assertNull(allowed.reason)

        // 终态已解除绑定：重复/过期响应不得再完成任何等待器。
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, "call-1", true))
        assertNull(ToolPermissionCoordinator.pendingConfirmation.value)
    }

    @Test
    fun unknownToolCallIdStaysUnknownAndDenialKeepsOriginalWaiterSemantics() = runTest {
        ToolPermissionCoordinator.isUiResumed = true
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val auth = observer.contextFor(turn) // toolCallId 无证据 → null，不猜测关联

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request()) } }
        runCurrent()
        assertNull(sink.permissions[0].toolCallId)

        assertEquals(CommandOutcome.Applied, observer.respond(turn, "call-1", false))
        runCurrent()
        assertEquals(ToolPermissionResponse.DENIED_BY_USER, confirm.await())
        val denied = sink.permissions.last()
        assertEquals(PermissionPhase.Denied, denied.phase)
        assertEquals("DENIED_BY_USER", denied.reason?.code)
    }

    @Test
    fun responderRequiresExactTurnAndRequestIdentity() = runTest {
        ToolPermissionCoordinator.isUiResumed = true
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val owner = turn("owner")
        val stranger = turn("stranger", epoch = 2L)
        val auth = observer.contextFor(owner)

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request()) } }
        runCurrent()

        // 同 requestId 但回合不同：不触碰该等待器，绑定保留。
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(stranger, "call-1", true))
        assertTrue(confirm.isActive)
        // 未登记 requestId 同样被拒。
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(owner, "other", true))
        // 精确身份才完成原等待器。
        assertEquals(CommandOutcome.Applied, observer.respond(owner, "call-1", true))
        runCurrent()
        assertEquals(ToolPermissionResponse.ALLOWED, confirm.await())
    }

    @Test
    fun declinedForwardKeepsBindingUntilTerminalLifecycleFact() = runTest {
        ToolPermissionCoordinator.isUiResumed = true
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val auth = observer.contextFor(turn)

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request()) } }
        runCurrent()

        // 旧响应渠道先完成等待器。
        assertTrue(ToolPermissionCoordinator.respond("call-1", true))
        // 观察侧转发得到 false（原等待器已完成）——绑定不得因此被删除。
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, "call-1", true))
        // 终态事实到达后才解除绑定。
        runCurrent()
        assertEquals(ToolPermissionResponse.ALLOWED, confirm.await())
        assertEquals(PermissionPhase.Allowed, sink.permissions.last().phase)
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, "call-1", true))
    }

    @Test
    fun backgroundChannelRoutesResponseToRegisteredActualWaiter() = runTest {
        ToolPermissionCoordinator.isUiResumed = false
        val overlayGate = CompletableDeferred<ToolPermissionResponse>()
        ToolPermissionCoordinator.backgroundConfirmationHandler = { overlayGate.await() }
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val auth = observer.contextFor(turn)

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request("call-2")) } }
        runCurrent()
        assertEquals(TOOL_CONFIRM_CHANNEL_BACKGROUND, sink.permissions[1].channel)

        var responderCalls = 0
        observer.registerBackgroundResponder { requestId, allowed ->
            responderCalls++
            assertEquals("call-2", requestId)
            assertTrue(allowed)
            true
        }
        // 后台渠道响应走 App 注册的实际等待器接缝。
        assertEquals(CommandOutcome.Applied, observer.respond(turn, "call-2", true))
        assertEquals(1, responderCalls)
        overlayGate.complete(ToolPermissionResponse.ALLOWED)
        runCurrent()
        assertEquals(ToolPermissionResponse.ALLOWED, confirm.await())
        assertEquals(PermissionPhase.Allowed, sink.permissions.last().phase)
    }

    @Test
    fun backgroundResponderRegistrationIsComparedByInstance() = runTest {
        ToolPermissionCoordinator.isUiResumed = false
        val gate = CompletableDeferred<ToolPermissionResponse>()
        ToolPermissionCoordinator.backgroundConfirmationHandler = { gate.await() }
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val auth = observer.contextFor(turn)
        val firstResponder: (String, Boolean) -> Boolean = { _, _ -> true }
        val secondResponder: (String, Boolean) -> Boolean = { _, _ -> false }
        observer.registerBackgroundResponder(firstResponder)

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request()) } }
        runCurrent()
        assertEquals(TOOL_CONFIRM_CHANNEL_BACKGROUND, sink.permissions[1].channel)

        // 实例不同：解除不误删当前注册，响应仍走 firstResponder。
        observer.unregisterBackgroundResponder(secondResponder)
        assertEquals(CommandOutcome.Applied, observer.respond(turn, "call-1", false))
        // 换绑为返回 false 的响应器：转发不完成等待器，也不删除绑定。
        observer.registerBackgroundResponder(secondResponder)
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, "call-1", true))
        // 恢复可用接缝后真正完成原渠道等待。
        observer.registerBackgroundResponder { _, _ -> true }
        assertEquals(CommandOutcome.Applied, observer.respond(turn, "call-1", true))
        gate.complete(ToolPermissionResponse.ALLOWED)
        runCurrent()
        assertEquals(ToolPermissionResponse.ALLOWED, confirm.await())
    }

    @Test
    fun downstreamFailureDoesNotChangeConfirmationOutcome() = runTest {
        ToolPermissionCoordinator.isUiResumed = true
        val observer = ConversationPermissionObserver(
            RuntimeFactSink { throw IllegalStateException("downstream boom") },
        )
        val turn = turn("t1")
        val auth = observer.contextFor(turn)

        val confirm = async { withContext(auth) { ToolPermissionCoordinator.confirm(request()) } }
        runCurrent()
        assertEquals(CommandOutcome.Applied, observer.respond(turn, "call-1", true))
        runCurrent()
        assertEquals(ToolPermissionResponse.ALLOWED, confirm.await())
    }

    // ── 屏控知情同意：真实等待器 ───────────────────────────────────────────

    @Test
    fun screenConsentExposesChannelAndCompletesRealWaiterByRequestId() = runTest {
        ToolPermissionCoordinator.isUiResumed = true
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val auth = observer.contextFor(turn).copy(toolCallId = "call-screen")

        val consent = async { withContext(auth) { ScreenControlConsent.request() } }
        runCurrent()

        assertEquals(2, sink.permissions.size)
        val requested = sink.permissions[0]
        assertEquals(PermissionKind.ScreenConsent, requested.kind)
        assertEquals(PermissionPhase.Requested, requested.phase)
        assertNull(requested.channel)
        val processing = sink.permissions[1]
        assertEquals(PermissionPhase.Processing, processing.phase)
        assertEquals(ScreenControlConsent.CHANNEL_FOREGROUND, processing.channel)
        assertEquals("call-screen", processing.toolCallId)
        assertTrue(ScreenControlConsent.pending.value)

        val requestId = requested.requestId
        assertEquals(CommandOutcome.Applied, observer.respond(turn, requestId, false))
        runCurrent()
        assertFalse(consent.await())
        val denied = sink.permissions.last()
        assertEquals(denied.requestId, requestId)
        assertEquals(PermissionPhase.Denied, denied.phase)
        assertEquals("DENIED_BY_USER", denied.reason?.code)
        assertFalse(ScreenControlConsent.pending.value)

        // 终态后旧响应不再命中。
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, requestId, false))
    }

    // ── 系统权限链：只读可观察、永不代替授权决定 ───────────────────────────

    @Test
    fun systemPermissionChainIsObservableAndResponseIsUnsupported() {
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val obs = observer.contextFor(turn).permissionObservation!!
        val permission = Permission.ROOT

        obs.onEvent(
            PermissionObservationEvent.RequestStarted(
                "r-sys", permission, listOf(Channel.ROOT_SHELL, Channel.SHIZUKU),
            ),
        )
        var fact = sink.permissions.last()
        assertEquals("r-sys", fact.requestId)
        assertEquals(turn, fact.turn)
        assertEquals(PermissionKind.SystemPermission, fact.kind)
        assertEquals("ROOT", fact.target)
        assertNull(fact.channel)
        assertEquals(PermissionPhase.Requested, fact.phase)
        assertTrue(fact.attempts.isEmpty())
        assertNull(fact.toolCallId)

        obs.onEvent(PermissionObservationEvent.ChannelStarted("r-sys", permission, Channel.ROOT_SHELL))
        fact = sink.permissions.last()
        assertEquals(PermissionPhase.Processing, fact.phase)
        assertEquals("ROOT_SHELL", fact.channel)

        // 系统权限没有 resolver：等待期间也不得把系统权限改为已授权。
        assertEquals(CommandOutcome.UnsupportedAction, observer.respond(turn, "r-sys", true))
        assertEquals(CommandOutcome.UnsupportedAction, observer.respond(turn, "r-sys", false))

        obs.onEvent(
            PermissionObservationEvent.ChannelAttempted(
                "r-sys",
                Attempt(permission, Channel.ROOT_SHELL, PermissionState.FAILED, "root denied"),
            ),
        )
        fact = sink.permissions.last()
        assertEquals(1, fact.attempts.size)
        assertEquals("root denied", fact.attempts.single().detail)

        val attempts = listOf(
            Attempt(permission, Channel.ROOT_SHELL, PermissionState.FAILED, "root denied"),
            Attempt(permission, Channel.SHIZUKU, PermissionState.GRANTED),
        )
        obs.onEvent(
            PermissionObservationEvent.RequestFinished(
                "r-sys", PermissionResult(permission, PermissionState.GRANTED, attempts),
            ),
        )
        fact = sink.permissions.last()
        assertEquals(PermissionPhase.Allowed, fact.phase)
        assertEquals(2, fact.attempts.size)
        // 即使最终成功，观察侧也保留早前失败渠道的诊断。
        assertEquals("root denied", fact.attempts.first().detail)
        assertNull(fact.reason)
        // 终态后不再命中任何 resolver。
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, "r-sys", true))
    }

    @Test
    fun systemPermissionHandlerThrownDiagnosticSurvivesEventualSuccess() {
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val obs = observer.contextFor(turn).permissionObservation!!
        val permission = Permission.OVERLAY

        obs.onEvent(
            PermissionObservationEvent.RequestStarted("r-h", permission, listOf(Channel.SYSTEM_DIALOG)),
        )
        obs.onEvent(
            PermissionObservationEvent.HandlerThrew(
                "r-h", permission, Channel.SYSTEM_DIALOG, IllegalStateException("handler boom"),
            ),
        )
        var fact = sink.permissions.last()
        assertEquals(PermissionPhase.Processing, fact.phase)
        assertEquals("SYSTEM_DIALOG", fact.channel)
        assertEquals("HANDLER_THREW", fact.reason?.code)

        obs.onEvent(
            PermissionObservationEvent.ChannelAttempted(
                "r-h", Attempt(permission, Channel.SYSTEM_DIALOG, PermissionState.FAILED),
            ),
        )
        obs.onEvent(
            PermissionObservationEvent.RequestFinished(
                "r-h",
                PermissionResult(
                    permission,
                    PermissionState.FAILED,
                    listOf(Attempt(permission, Channel.SYSTEM_DIALOG, PermissionState.FAILED)),
                ),
            ),
        )
        fact = sink.permissions.last()
        assertEquals(PermissionPhase.Failed, fact.phase)
        assertTrue(
            fact.attempts.single().detail!!.contains("handlerThrew=IllegalStateException"),
        )
    }

    @Test
    fun systemPermissionCancellationIsTerminalAndNonPolluting() {
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        val turn = turn("t1")
        val obs = observer.contextFor(turn).permissionObservation!!
        val permission = Permission.ACCESSIBILITY

        obs.onEvent(
            PermissionObservationEvent.RequestStarted("r-c", permission, listOf(Channel.JUMP_SETTINGS)),
        )
        obs.onEvent(PermissionObservationEvent.RequestCancelled("r-c", permission))
        val fact = sink.permissions.last()
        assertEquals(PermissionPhase.Cancelled, fact.phase)
        assertEquals("CANCELLED", fact.reason?.code)
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(turn, "r-c", true))
    }

    // ── 生命周期围栏：旧回合迟到事实不污染新回合 ────────────────────────────

    @Test
    fun clearedTurnFencesLateFactsAndOldCleanupCannotRemoveSuccessor() {
        val sink = RecordingSink()
        val observer = ConversationPermissionObserver(sink)
        observer.registerBackgroundResponder { _, _ -> true }
        val oldTurn = turn("old")
        val newTurn = turn("new", epoch = 2L)

        val oldAuth = observer.contextFor(oldTurn)
        oldAuth.sink.accept(
            RuntimeFact.PermissionReported(observation("shared", oldTurn, PermissionPhase.Requested)),
        )
        oldAuth.sink.accept(
            RuntimeFact.PermissionReported(
                observation(
                    "shared",
                    oldTurn,
                    PermissionPhase.Processing,
                    channel = TOOL_CONFIRM_CHANNEL_BACKGROUND,
                ),
            ),
        )
        assertEquals(CommandOutcome.Applied, observer.respond(oldTurn, "shared", true))

        observer.clearTurn(oldTurn)
        // 迟到 Requested（已关闭生命周期）不得重建绑定。
        oldAuth.sink.accept(
            RuntimeFact.PermissionReported(observation("shared", oldTurn, PermissionPhase.Requested)),
        )
        assertEquals(CommandOutcome.IgnoredStaleTarget, observer.respond(oldTurn, "shared", true))

        // 新回合复用同一 requestId 并重新绑定。
        val newAuth = observer.contextFor(newTurn)
        newAuth.sink.accept(
            RuntimeFact.PermissionReported(observation("shared", newTurn, PermissionPhase.Requested)),
        )
        newAuth.sink.accept(
            RuntimeFact.PermissionReported(
                observation(
                    "shared",
                    newTurn,
                    PermissionPhase.Processing,
                    channel = TOOL_CONFIRM_CHANNEL_BACKGROUND,
                ),
            ),
        )
        // 旧回合迟到终态：不得移除新回合绑定。
        oldAuth.sink.accept(
            RuntimeFact.PermissionReported(observation("shared", oldTurn, PermissionPhase.Denied)),
        )
        assertEquals(CommandOutcome.Applied, observer.respond(newTurn, "shared", true))
        // 下游仍按身份收到旧回合的迟到事实（由 reducer 归属），观察映射不重建。
        assertTrue(sink.permissions.any { it.turn == oldTurn && it.phase == PermissionPhase.Denied })
    }
}
