package com.niki914.okai.runtime

import com.niki914.okai.loop.AgentLoop
import com.niki914.okai.mcp.McpClient
import com.niki914.okai.protocol.ProtocolRegistry
import com.niki914.okai.session.Session
import com.niki914.okai.tool.ToolCallInterceptor
import com.niki914.okai.tool.ToolRegistry

/**
 * Assembly point of the whole library. open() builds one from config plus
 * defaults; tests build one with fakes, so every dependency is replaceable
 * on the JVM. The loop reads registry and chain references from here.
 * forceStopHook terminates host-managed tool resources (processes,
 * sessions) when a turn is cancelled; coroutine cancellation alone cannot
 * kill child processes. The Okai coordinator calls it before cancelling
 * the turn job (kill-then-stop). null means the host declares there are
 * no such resources; hosts that run processes (Nexus) must not be null.
 *
 * Design source: independent design; full-facade testability requirement
 * from kai PRD success criteria 6; hook requirement validated in the Nexus
 * stop handling (PyRuntime.kill / TerminalSessionPool.closeAll).
 */
interface OkaiDependencies {

    val agentLoop: AgentLoop

    val session: Session

    val protocolRegistry: ProtocolRegistry

    val toolRegistry: ToolRegistry

    val interceptors: List<ToolCallInterceptor>

    val mcpClient: McpClient

    val clock: Clock

    val forceStopHook: ForceStopHook?
}
