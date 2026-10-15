// 保护：Okia.append 的单条追加拓扑、StateFlow 广播联动以及活跃回合/关闭态的互斥防御
package com.niki914.okia

import com.niki914.okia.fake.FakeAgentLoop
import com.niki914.okia.fake.FakeHttpEngine
import com.niki914.okia.fake.FakeProtocolMapper
import com.niki914.okia.fake.StubMcpClient
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.CompletionReason
import com.niki914.okia.loop.RealAgentLoop
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.protocol.ProtocolEvent
import com.niki914.okia.transport.HttpEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RealOkiaAppendTest {

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun deps(
        mapper: ProtocolCompatMapper = FakeProtocolMapper(emptyList<ProtocolEvent>()),
        loop: AgentLoop = RealAgentLoop(),
        engine: HttpEngine = FakeHttpEngine()
    ) = object : OkiaDependencies {
        override val agentLoop = loop
        override val protocolMapper = mapper
        override val mcpClient = StubMcpClient
    }

    private fun openOkia(
        scope: CoroutineScope,
        loop: AgentLoop = RealAgentLoop(),
        engine: HttpEngine = FakeHttpEngine()
    ): RealOkia = RealOkia(
        dependencies = deps(loop = loop, engine = engine),
        restore = null,
        initialConfig = OkiaConfig.Builder().apply {
            endpoint = "https://api.test/v1"
            apiKey = "test-key"
            model = "test-model"
            httpEngine = engine
        }.build(),
        turnScope = scope
    )

    @Test
    fun appendsMessageAndChainsParentIdCorrectly() = runTest {
        val okia = openOkia(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val entry1 = okia.append(user("first"))
        assertNull(entry1.parentId)

        val entry2 = okia.append(user("second"))
        assertEquals(entry1.id, entry2.parentId)

        val history = okia.conversation.value.history
        assertEquals(2, history.size)
        assertEquals(user("first"), history[0].message)
        assertEquals(user("second"), history[1].message)
    }

    @Test
    fun publishesStateFlowOnAppend() = runTest {
        val okia = openOkia(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        assertEquals(0, okia.conversation.value.history.size)

        okia.append(user("hello"))

        val snapshot = okia.conversation.value
        assertEquals(1, snapshot.history.size)
        assertEquals(user("hello"), snapshot.history.single().message)
    }

    @Test
    fun rejectsAppendDuringActiveTurn() = runTest {
        val suspendGate = CompletableDeferred<Unit>()
        val loop = FakeAgentLoop { _, _ ->
            suspendGate.await()
            TurnResult.Completed(CompletionReason.Stop)
        }
        val okia = openOkia(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            loop = loop
        )

        val sendJob = async(Dispatchers.Default) {
            okia.send("hi") {}
        }

        // 等待回合确已进入 active 状态
        while (okia.conversation.value.history.isEmpty()) {
            kotlinx.coroutines.delay(10)
        }

        try {
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    okia.append(user("concurrent"))
                }
            }
        } finally {
            suspendGate.complete(Unit)
            sendJob.await()
            okia.close()
        }
    }

    @Test
    fun rejectsAppendWhenClosed() = runTest {
        val okia = openOkia(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        okia.close()

        assertThrows(IllegalStateException::class.java) {
            runTest {
                okia.append(user("dead"))
            }
        }
    }
}
