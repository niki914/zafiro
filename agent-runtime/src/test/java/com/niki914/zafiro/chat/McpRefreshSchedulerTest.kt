package com.niki914.zafiro.chat

import com.niki914.zafiro.chat.util.SilentLoggerRule
import com.niki914.okia.Okia
import com.niki914.okia.OkiaConfig
import com.niki914.okia.TurnOptions
import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * McpRefreshScheduler 单测（问题 4 修复，虚拟时间）：失败退避重试、
 * 部分失败不算成功签名、in-flight 期间配置变化不吞。
 * 不经过 LLMController / RealOkia（refreshMcpTools 持 RealOkia mutex 整个
 * 网络往返，#125——集成级构造 in-flight 窗口会死锁，故在此用假 Okia 隔离）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpRefreshSchedulerTest {

    @get:Rule
    val silentLogger = SilentLoggerRule()

    private class FakeOkia(
        var refreshResult: suspend () -> McpRefreshResult,
    ) : Okia {
        var refreshCalls = 0
            private set

        override val conversation: StateFlow<Conversation> =
            MutableStateFlow(Conversation(id = "s", leafId = null, history = emptyList()))
        override val events: SharedFlow<TurnEvent> = MutableSharedFlow()

        override suspend fun refreshMcpTools(): McpRefreshResult {
            refreshCalls++
            return refreshResult()
        }

        override suspend fun send(
            text: String,
            options: TurnOptions?,
            onEvent: suspend (TurnEvent) -> Unit,
        ): TurnResult = error("not used")

        override suspend fun stop() = error("not used")
        override suspend fun rewind(entryId: String) = error("not used")
        override suspend fun export(): SessionSnapshot = error("not used")
        override suspend fun update(block: OkiaConfig.Builder.() -> Unit) = error("not used")
        override suspend fun config(): OkiaConfig = error("not used")
        override suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot = error("not used")
        override suspend fun close() = error("not used")
    }

    private fun ok(result: McpRefreshResult) = McpRefreshResult(result.refreshedServers, result.failedServers)

    @Test
    fun failureThenSuccess_retriesWithBackoff() = runTest {
        var call = 0
        val okia = FakeOkia(
            refreshResult = {
                call++
                if (call == 1) throw java.io.IOException("connection refused")
                McpRefreshResult(listOf("s1"), emptyList())
            }
        )
        val scheduler = McpRefreshScheduler(backgroundScope)

        scheduler.schedule(okia, "sig-A")
        runCurrent() // 首次刷新失败 → 退避 1s 挂起
        assertEquals(1, okia.refreshCalls)

        advanceTimeBy(1_000)
        runCurrent() // 退避结束 → 重试成功
        assertEquals(2, okia.refreshCalls)

        // 成功签名已更新：同签名不再触发
        scheduler.schedule(okia, "sig-A")
        runCurrent()
        assertEquals(2, okia.refreshCalls)
    }

    @Test
    fun partialFailure_doesNotMarkSignatureSuccess() = runTest {
        // 部分服务器失败（failedServers 非空）不算成功 → 退避重试
        val okia = FakeOkia(
            refreshResult = { McpRefreshResult(listOf("s1"), listOf("s2")) }
        )
        val scheduler = McpRefreshScheduler(backgroundScope)

        scheduler.schedule(okia, "sig-A")
        runCurrent()
        assertEquals(1, okia.refreshCalls)

        advanceTimeBy(1_000)
        runCurrent() // 重试发生（失败不被永久跳过）
        assertEquals(2, okia.refreshCalls)
    }

    @Test
    fun configChangeDuringInflight_continuesWithLatest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val okia = FakeOkia(
            refreshResult = {
                gate.await()
                McpRefreshResult(listOf("s1"), emptyList())
            }
        )
        val scheduler = McpRefreshScheduler(backgroundScope)

        scheduler.schedule(okia, "sig-A")
        runCurrent() // A 刷新挂起（in-flight）
        assertEquals(1, okia.refreshCalls)

        // 配置 A → B（in-flight 期间）：只更新 desired，不吞
        scheduler.schedule(okia, "sig-B")
        runCurrent()
        assertEquals(1, okia.refreshCalls) // inFlight 挡住，不重复启动

        gate.complete(Unit)
        runCurrent() // A 完成 → 检查 desired=B ≠ success → 续刷 B
        assertEquals(2, okia.refreshCalls)

        // B 成功 → 同签名不再触发
        scheduler.schedule(okia, "sig-B")
        runCurrent()
        assertEquals(2, okia.refreshCalls)
    }

    @Test
    fun activeTurnConflict_usesShortBackoff() = runTest {
        var call = 0
        val okia = FakeOkia(
            refreshResult = {
                call++
                if (call == 1) throw IllegalStateException("cannot refreshMcpTools during active turn")
                McpRefreshResult(listOf("s1"), emptyList())
            }
        )
        val scheduler = McpRefreshScheduler(backgroundScope)

        scheduler.schedule(okia, "sig-A")
        runCurrent()
        assertEquals(1, okia.refreshCalls)

        advanceTimeBy(500) // 撞回合短退避
        runCurrent()
        assertEquals(2, okia.refreshCalls)
    }

    @Test
    fun forceSchedule_bypassesSignatureDedup() = runTest {
        // 会话切换预热（#switch-refresh）：签名已成功也强制重刷；
        // in-flight 去重仍生效（gate 挂住第二轮刷新验证不叠加）
        var gate: CompletableDeferred<Unit>? = null
        val okia = FakeOkia(
            refreshResult = {
                gate?.await()
                McpRefreshResult(listOf("s1"), emptyList())
            }
        )
        val scheduler = McpRefreshScheduler(backgroundScope)

        scheduler.schedule(okia, "sig-A")
        runCurrent()
        assertEquals(1, okia.refreshCalls)

        // 同签名不触发
        scheduler.schedule(okia, "sig-A")
        runCurrent()
        assertEquals(1, okia.refreshCalls)

        // force 跳过去重，重刷一次（gate 挂住保持 in-flight）
        gate = CompletableDeferred()
        scheduler.schedule(okia, "sig-A", force = true)
        runCurrent()
        assertEquals(2, okia.refreshCalls)

        // 刷新进行中再 force：inFlight 去重，不叠加
        scheduler.schedule(okia, "sig-A", force = true)
        runCurrent()
        assertEquals(2, okia.refreshCalls)

        gate?.complete(Unit)
        runCurrent()
    }
}
