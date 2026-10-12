package com.niki914.zafiro.chat.agentic

import com.niki914.zafiro.api.AgentControl
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 隐式 Agent 租户上下文凭证。
 *
 * 挂载在工具执行协程链路上，Agent 与大模型端完全无感知（入参与 Schema 保持原样）。
 * 底层资源池（如 [TerminalSessionPool]）据此实施租户沙箱隔离，防止跨会话越权操作；
 * 预检门禁（如 [ToolExecutionPreflight]）据此获取对应会话的 [AgentControl] 执行定向审批。
 */
class AgentTenantContext(
    val tenantId: String,
    val agentControl: AgentControl? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentTenantContext>
}

/** 获取当前协程上下文中的 Agent 租户标识，若脱离 Agent 运行环境则返回 null。 */
suspend inline fun currentAgentTenantId(): String? = coroutineContext[AgentTenantContext]?.tenantId

/** 获取当前协程上下文中的 Agent 控制面，若脱离 Agent 运行环境则返回 null。 */
suspend inline fun currentAgentControl(): AgentControl? =
    coroutineContext[AgentTenantContext]?.agentControl
