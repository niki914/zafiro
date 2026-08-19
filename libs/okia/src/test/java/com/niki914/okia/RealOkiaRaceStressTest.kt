package com.niki914.okia

import com.niki914.okia.fake.FakeAgentLoop
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.StubMcpClient
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.Message
import com.niki914.okia.message.StopReason
import com.niki914.okia.protocol.ProtocolEvent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * TOCTOU 回归测试（评审发现 5）：rewind 的 check（mutex 内）与 tree.rewind
 * （锁外）之间存在窗口，并发 send 可在两者之间启动。
 * 检测方式与交错顺序无关：最终树的 leaf 链（去掉 Assistant）必须等于 loop
 * 收到的 history。竞态命中时 rewind 成功、assistant 挂在被回退的旧 leaf 下，
 * 新输入从可见历史消失（seen.size=3, final.size=1）。
 * 概率型压力测试（3000 轮并发，实测 4/4 次运行均命中 8-10 次）；命中即失败。
 */
class RealOkiaRaceStressTest {

    private fun openOkia(loop: FakeAgentLoop): RealOkia = RealOkia(
        dependencies = object : OkiaDependencies {
            override val agentLoop = loop
            override val protocolMapper =
                FakeProtocolMapper(listOf(ProtocolEvent.Completed(stopReason = StopReason.Stop)))
            override val mcpClient = StubMcpClient
        },
        restore = null,
        initialConfig = OkiaConfig.Builder().apply {
            endpoint = "https://api.test/v1"
            apiKey = "test-key"
            model = "test-model"
            httpEngine = FakeHttpEngine()
        }.build()
    )

    @Test
    fun rewindConcurrentWithSendKeepsTreeConsistent() = runBlocking {
        val iterations = 3000
        var violations = 0
        for (i in 0 until iterations) {
            val seenHistory = AtomicReference<List<Message>>(emptyList())
            // gate 引用：setup 用已完成的（send 立即返回），竞态用未完成的（保持回合活跃）
            val gateRef = AtomicReference(
                CompletableDeferred<Unit>().also { it.complete(Unit) }
            )
            val loop = FakeAgentLoop { request, _ ->
                seenHistory.set(request.history)
                gateRef.get().await()
                TurnResult.Completed(CompletionReason.Stop)
            }
            val okia = openOkia(loop)

            // setup：两轮历史 [User(a), User(b)]，leaf=b
            okia.send("a") {}
            okia.send("b") {}
            val entryA = okia.conversation.value.history.first().id

            // 竞态：新 gate（未完成）→ send 的回合保持活跃；并发 rewind + send
            gateRef.set(CompletableDeferred())
            seenHistory.set(emptyList())

            val sendJob = async(Dispatchers.Default) { runCatching { okia.send("c") {} } }
            val rewindJob = async(Dispatchers.Default) { runCatching { okia.rewind(entryA) } }

            // 等 rewind 落定（成功或抛异常），再放行 send 的回合
            rewindJob.join()
            gateRef.get().complete(Unit)
            sendJob.await()

            // 检测：final 树（去 Assistant）== loop 收到的 history。
            // 合法交错（rewind 先于 send / send 先于 rewind 的 check）都满足；
            // 竞态命中时 rewind 成功且 assistant 挂在旧 leaf 下，seen 更长。
            val finalHistory = okia.conversation.value.history
                .filter { it.message !is Message.Assistant }
                .map { it.message }
            val seen = seenHistory.get()
            if (seen.isNotEmpty() && finalHistory != seen) {
                violations++
                if (violations == 1) {
                    println("VIOLATION at iteration $i: seen.size=${seen.size} final.size=${finalHistory.size}")
                }
            }
            okia.close()
        }
        org.junit.Assert.assertEquals(
            "rewind 与 send 并发不得造成树与 loop 历史不一致（TOCTOU 竞态）",
            0, violations
        )
    }
}
