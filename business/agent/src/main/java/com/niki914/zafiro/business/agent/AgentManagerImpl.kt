package com.niki914.zafiro.business.agent

import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.chat.AgentSessionEngine
import com.niki914.zafiro.chat.LLMController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * [AgentManager] 的实现。
 *
 * 持有主前台长驻会话 [main]，并提供瞬态任务对话工厂 [createTaskAgent]。
 * 控制面通过 [CoordinatedAgentControl] 实施先到先得的状态聚合与焦点仲裁。
 */
class AgentManagerImpl(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AgentManager {

    override val main: Agent = AgentImpl(
        engine = LLMController.defaultEngine,
        isPersistent = true,
    )

    private val coordinator = CoordinatedAgentControl(main, scope)

    override val control: AgentControl get() = coordinator

    override fun createTaskAgent(tag: String): Agent {
        val taskAgent = AgentImpl(
            engine = AgentSessionEngine(),
            isPersistent = false,
        )
        coordinator.trackTaskAgent(taskAgent)
        return taskAgent
    }
}
