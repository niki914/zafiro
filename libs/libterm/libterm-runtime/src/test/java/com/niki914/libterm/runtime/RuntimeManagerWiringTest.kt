package com.niki914.libterm.runtime

import com.niki914.libterm.BackendAvailability
import com.niki914.libterm.BackendStartResult
import com.niki914.libterm.Clock
import com.niki914.libterm.IdGenerator
import com.niki914.libterm.OpenResult
import com.niki914.libterm.OutputChunk
import com.niki914.libterm.PrivilegeProvider
import com.niki914.libterm.SendResult
import com.niki914.libterm.TerminalBackend
import com.niki914.libterm.TerminalBytes
import com.niki914.libterm.TerminalFailure
import com.niki914.libterm.TerminalIdentity
import com.niki914.libterm.TerminalManager
import com.niki914.libterm.TerminalOpenOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RuntimeManagerWiringTest {

    @Test
    fun `backend start failure is returned without wrapping`() = runTest {
        val startupFailure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.Su,
            message = "boom",
        )
        val factory = RecordingBackendFactory(startupFailure)
        val manager = createManager(factory)

        val result = manager.open(
            identity = TerminalIdentity.Su,
            openOptions = TerminalOpenOptions(cwd = "/data/local/tmp"),
        )

        val failure = assertIs<OpenResult.Failure>(result)
        assertSame(startupFailure, failure.failure)
        assertEquals(mapOf(TerminalIdentity.Su to 1), factory.createCounts)
        assertEquals(
            listOf(TerminalOpenOptions(cwd = "/data/local/tmp")),
            factory.startedOptions(TerminalIdentity.Su),
        )
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun `shizuku startup failure does not create user or root fallback backends`() = runTest {
        val startupFailure = TerminalFailure.StartupFailed(
            identity = TerminalIdentity.Shizuku,
            message = "shizuku failed",
        )
        val factory = RecordingBackendFactory(startupFailure)
        val manager = createManager(factory)

        val result = manager.open(
            identity = TerminalIdentity.Shizuku,
            openOptions = TerminalOpenOptions(),
        )

        val failure = assertIs<OpenResult.Failure>(result)
        assertSame(startupFailure, failure.failure)
        assertEquals(1, factory.createCount(TerminalIdentity.Shizuku))
        assertEquals(0, factory.createCount(TerminalIdentity.User))
        assertEquals(0, factory.createCount(TerminalIdentity.Su))
        assertEquals(listOf(TerminalOpenOptions()), factory.startedOptions(TerminalIdentity.Shizuku))
        assertTrue(manager.list().isEmpty())
    }

    private fun TestScope.createManager(factory: RecordingBackendFactory): TerminalManager {
        return TerminalManager(
            privilegeProvider = AvailableProvider,
            idGenerator = SequentialIdGenerator(),
            clock = FixedClock,
            scope = this,
            backendFactory = factory::create,
        )
    }

    private object AvailableProvider : PrivilegeProvider {
        override suspend fun getAvailability(identity: TerminalIdentity): BackendAvailability {
            return BackendAvailability.Available
        }
    }

    private class SequentialIdGenerator : IdGenerator {
        private var next = 0

        override fun nextId(): String {
            next += 1
            return "session-$next"
        }
    }

    private object FixedClock : Clock {
        override fun nowMillis(): Long = 100L
    }

    private class RecordingBackendFactory(
        private val startupFailure: TerminalFailure,
    ) {
        val createCounts = mutableMapOf<TerminalIdentity, Int>()
        private val startedOptions = mutableMapOf<TerminalIdentity, MutableList<TerminalOpenOptions>>()

        fun createCount(identity: TerminalIdentity): Int {
            return createCounts[identity] ?: 0
        }

        fun startedOptions(identity: TerminalIdentity): List<TerminalOpenOptions> {
            return startedOptions[identity].orEmpty()
        }

        fun create(
            identity: TerminalIdentity,
            openOptions: TerminalOpenOptions,
        ): TerminalBackend {
            createCounts[identity] = createCount(identity) + 1
            return FailingBackend(
                identity = identity,
                startupFailure = startupFailure,
                recordOpenOptions = { openOptions ->
                    startedOptions.getOrPut(identity) { mutableListOf() } += openOptions
                },
            )
        }
    }

    private class FailingBackend(
        override val identity: TerminalIdentity,
        private val startupFailure: TerminalFailure,
        private val recordOpenOptions: (TerminalOpenOptions) -> Unit,
    ) : TerminalBackend {
        override val output: Flow<OutputChunk> = emptyFlow()

        override suspend fun start(openOptions: TerminalOpenOptions): BackendStartResult {
            recordOpenOptions(openOptions)
            return BackendStartResult.Failed(startupFailure)
        }

        override suspend fun send(input: TerminalBytes): SendResult {
            return SendResult.Failed(
                TerminalFailure.AlreadyClosed(
                    identity = identity,
                    message = "not started",
                ),
            )
        }

        override suspend fun close() = Unit

        override suspend fun awaitExit(): TerminalFailure? = startupFailure
    }
}
