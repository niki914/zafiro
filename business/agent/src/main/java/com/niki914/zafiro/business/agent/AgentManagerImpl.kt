package com.niki914.zafiro.business.agent

import com.niki914.logging.Logger
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentControl
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.chat.AgentSessionEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * [AgentManager] 的实现。
 *
 * 维护内存活跃 Agent 容器 [activeAgents]，主前台 [main] 动态指向当前眼前的会话。
 * 用户切换会话时，旧会话在后台继续静默执行；切回时直接复用正在运行或已跑完的 Agent。
 */
class AgentManagerImpl(
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, throwable ->
            Logger.e(
                "niki914_zafiro_AgentManagerImpl",
                "Uncaught exception in AgentManager scope: ${throwable.message}",
                throwable
            )
        }
    ),
) : AgentManager {

    private companion object {
        const val LOG_TAG = "niki914_zafiro_AgentManagerImpl"
        const val MAX_IDLE_BACKGROUND_AGENTS = 3
    }

    private val activeAgents = ConcurrentHashMap<String, Agent>()
    private val trackedAgentIds = ConcurrentHashMap.newKeySet<String>()
    private val statusTrackingJobs = ConcurrentHashMap<String, Job>()
    private val lastAccessedMs = ConcurrentHashMap<String, Long>()
    private var scratchRegistrationJob: Job? = null
    private var scratchAgent: Agent = createPersistentAgent { scratchRegistrationJob = it }

    private val _mainFlow = MutableStateFlow(scratchAgent)
    override val mainFlow: StateFlow<Agent> = _mainFlow.asStateFlow()

    override val main: Agent get() = _mainFlow.value

    private val _activeStates = MutableStateFlow<Map<String, AgentState>>(emptyMap())
    override val activeStates: StateFlow<Map<String, AgentState>> = _activeStates.asStateFlow()

    private val coordinator = CoordinatedAgentControl(mainFlow, scope)

    override val control: AgentControl get() = coordinator

    override suspend fun switchTo(conversationId: String): Agent {
        val existing = activeAgents[conversationId]
        val target = if (existing != null) {
            Logger.i(LOG_TAG, "reusing active agent id=$conversationId")
            existing
        } else {
            Logger.i(LOG_TAG, "loading new agent into container id=$conversationId")
            val newAgent = createPersistentAgent()
            try {
                newAgent.load(ConversationId(conversationId))
            } catch (t: Throwable) {
                newAgent.release()
                throw t
            }
            registerActiveAgent(conversationId, newAgent)
            newAgent
        }
        lastAccessedMs[conversationId] = System.currentTimeMillis()
        _mainFlow.value = target
        trimIdleBackgroundAgents()
        return target
    }

    override fun startNewConversation(): Agent {
        val prev = scratchAgent
        if (prev.conversation.value.id == null) {
            scratchRegistrationJob?.cancel()
            prev.stop()
            prev.release()
        }
        val next = createPersistentAgent { scratchRegistrationJob = it }
        scratchAgent = next
        _mainFlow.value = next
        return next
    }

    override fun createTaskAgent(isPersistent: Boolean): Agent {
        return if (isPersistent) {
            createPersistentAgent()
        } else {
            AgentImpl(
                engine = AgentSessionEngine(),
                isPersistent = false,
            )
        }
    }

    override fun destroy(conversationId: String) {
        Logger.i(LOG_TAG, "destroy conversation agent id=$conversationId")
        val agent = activeAgents.remove(conversationId)
        statusTrackingJobs.remove(conversationId)?.cancel()
        trackedAgentIds.remove(conversationId)
        lastAccessedMs.remove(conversationId)
        _activeStates.update { current -> current - conversationId }

        agent?.let {
            it.stop()
            it.release()
        }

        if (_mainFlow.value === agent) {
            startNewConversation()
        }
    }

    private val trimMutex = Mutex()

    private suspend fun trimIdleBackgroundAgents() {
        trimMutex.withLock {
            val currentMainId = _mainFlow.value.conversation.value.id?.value
            val idleBackgroundCandidates = activeAgents.entries
                .filter { (id, agent) ->
                    id != currentMainId && agent.status.value is AgentState.Idle
                }
                .sortedBy { (id, _) -> lastAccessedMs[id] ?: 0L }

            val excessCount = idleBackgroundCandidates.size - MAX_IDLE_BACKGROUND_AGENTS
            if (excessCount > 0) {
                idleBackgroundCandidates.take(excessCount).forEach { (id, agent) ->
                    Logger.i(LOG_TAG, "evicting idle background agent id=$id")
                    activeAgents.remove(id)
                    statusTrackingJobs.remove(id)?.cancel()
                    trackedAgentIds.remove(id)
                    lastAccessedMs.remove(id)
                    _activeStates.update { it - id }
                    agent.release()
                }
            }
        }
    }

    private fun registerActiveAgent(id: String, agent: Agent) {
        activeAgents[id] = agent
        lastAccessedMs[id] = System.currentTimeMillis()
        if (trackedAgentIds.add(id)) {
            val job = scope.launch {
                agent.status.collect { state ->
                    _activeStates.update { current ->
                        current + (id to state)
                    }
                    if (state is AgentState.Idle) {
                        trimIdleBackgroundAgents()
                    }
                }
            }
            statusTrackingJobs[id] = job
        }
    }

    private fun createPersistentAgent(onJobCreated: ((Job) -> Unit)? = null): Agent {
        val agent = AgentImpl(
            engine = AgentSessionEngine(),
            isPersistent = true,
        )
        val job = scope.launch {
            val conv = agent.conversation.first { !it.id?.value.isNullOrBlank() }
            val id = conv.id?.value
            if (!id.isNullOrBlank()) {
                registerActiveAgent(id, agent)
            }
        }
        onJobCreated?.invoke(job)
        return agent
    }
}
