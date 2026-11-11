package com.tricreta.zafiro.autonomy.run

import com.niki914.logging.Logger
import com.niki914.zafiro.api.Agent
import com.niki914.zafiro.api.AgentDelegator
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.Approver
import com.niki914.zafiro.api.DelegateAgentInfo
import com.niki914.zafiro.api.DelegateResult
import com.niki914.zafiro.api.TurnStart
import com.niki914.zafiro.api.model.AgentState
import com.niki914.zafiro.api.model.ApprovalDecision
import com.niki914.zafiro.api.model.ApprovalRequest
import com.niki914.zafiro.api.model.Draft
import com.niki914.zafiro.api.model.TurnBlock
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.service.requireService
import com.tricreta.zafiro.autonomy.core.AgentBrief
import com.tricreta.zafiro.autonomy.core.AgentNames
import com.tricreta.zafiro.autonomy.core.Breach
import com.tricreta.zafiro.autonomy.core.Budget
import com.tricreta.zafiro.autonomy.core.BudgetTracker
import com.tricreta.zafiro.autonomy.core.StatusKind
import com.tricreta.zafiro.autonomy.core.StatusProtocol
import com.tricreta.zafiro.autonomy.data.AgentProfileEntity
import com.tricreta.zafiro.autonomy.data.AutonomyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs an agent profile on behalf of another agent. Each call gets its own task agent (own conversation, own budget), so
 * agents really work in parallel; only agents flagged usesScreen are serialised, because there is one screen.
 * Delegated agents cannot approve anything: every confirmation request is denied (nobody is watching).
 */
class DelegateRunner(
    private val repo: AutonomyRepository,
    private val scope: CoroutineScope,
) : AgentDelegator {

    private val concurrency = Semaphore(MAX_CONCURRENT)
    private val screenLock = Mutex()
    private val background = ConcurrentHashMap<String, CompletableDeferred<DelegateResult>>()

    override suspend fun list(): List<DelegateAgentInfo> =
        repo.agents().filter { it.enabled }.map { DelegateAgentInfo(it.name, it.instructions.lineSequence().first().take(120), it.usesScreen) }

    override suspend fun call(agent: String, task: String, background: Boolean): DelegateResult {
        if (task.isBlank()) return DelegateResult("ERROR", "Give the agent a task.")
        val profiles = repo.agents().filter { it.enabled }
        val name = AgentNames.find(agent, profiles.map { it.name })
            ?: return DelegateResult("ERROR", "No enabled agent named \"$agent\". Available: ${profiles.joinToString { it.name }.ifEmpty { "none" }}.")
        val profile = profiles.first { it.name == name }
        if (!background) return runOnce(profile, task)

        val handle = UUID.randomUUID().toString().take(8)
        val deferred = CompletableDeferred<DelegateResult>()
        this.background[handle] = deferred
        scope.launch {
            val result = try {
                runOnce(profile, task)
            } catch (e: CancellationException) {
                DelegateResult("ERROR", "Cancelled.")
            } catch (t: Throwable) {
                DelegateResult("ERROR", "Crashed: ${t.message}")
            }
            deferred.complete(result.copy(handle = handle))
        }
        return DelegateResult("RUNNING", "Started \"${profile.name}\" in the background.", handle)
    }

    override suspend fun result(handle: String, waitMs: Long): DelegateResult {
        val d = background[handle] ?: return DelegateResult("ERROR", "Unknown handle $handle.")
        val done = if (d.isCompleted) d.await() else withTimeoutOrNull(waitMs.coerceIn(0, MAX_WAIT_MS)) { d.await() }
        return done?.also { background.remove(handle) } ?: DelegateResult("RUNNING", "Still working.", handle)
    }

    private suspend fun runOnce(profile: AgentProfileEntity, task: String): DelegateResult =
        concurrency.withPermit {
            if (profile.usesScreen) screenLock.withLock { execute(profile, task) } else execute(profile, task)
        }

    private suspend fun execute(profile: AgentProfileEntity, task: String): DelegateResult {
        val manager: AgentManager = requireService()
        val agent = manager.createTaskAgent(isPersistent = true)
        val approver = object : Approver {
            override suspend fun decide(request: ApprovalRequest): ApprovalDecision = ApprovalDecision.Deny
        }
        agent.addApprover(approver)
        val startMs = System.currentTimeMillis()
        val tracker = BudgetTracker(Budget(maxSteps = MAX_STEPS, maxDurationMs = MAX_MS, maxTokens = MAX_TOKENS), startMs)
        var breach: Breach? = null
        try {
            if (profile.configId.isNotBlank()) agent.bindConfig(profile.configId)
            val prompt = AgentBrief.compose(
                profile.name, profile.instructions,
                profile.tools.split(',').filter { it.isNotBlank() }, profile.skills.split(',').filter { it.isNotBlank() },
                task, delegated = true,
            )
            coroutineScope {
                val watchdog = launch {
                    while (isActive) {
                        delay(1_000)
                        tracker.check(System.currentTimeMillis())?.let { breach = it; agent.stop(); return@launch }
                    }
                }
                val meters = launch {
                    launch { agent.conversation.collect { c -> tracker.setSteps(c.turns.sumOf { t -> t.blocks.count { it is TurnBlock.Tool } }) } }
                    launch { agent.tokenUsage.collect { tracker.setTokens(it.billable) } }
                }
                try {
                    agent.updateDraft { Draft(text = prompt) }
                    var tries = 0
                    while (agent.stream() == TurnStart.Busy && tries++ < 40) delay(50)
                    val ended = withTimeoutOrNull(MAX_MS + 60_000) {
                        agent.status.first { it.isRunning || (it is AgentState.Idle && it.lastOutcome != null) }
                        agent.status.first { !it.isRunning }
                        true
                    }
                    if (ended == null) { breach = breach ?: Breach.DURATION; agent.stop() }
                } finally {
                    watchdog.cancel(); meters.cancel()
                }
            }
            val turn = agent.conversation.value.turns.lastOrNull()
            val text = turn?.blocks?.filterIsInstance<TurnBlock.Text>()?.lastOrNull()?.text.orEmpty()
            val failure = turn?.blocks?.filterIsInstance<TurnBlock.Failure>()?.lastOrNull()
            val status = StatusProtocol.parse(text)
            return when {
                breach != null -> DelegateResult("FAILED", "Stopped: limit reached ($breach). Partial report: ${status.cleanedText.take(MAX_TEXT)}")
                failure != null -> DelegateResult("FAILED", "The agent failed (${failure.code}): ${failure.message.orEmpty()}")
                status.kind == StatusKind.NEEDS_HUMAN -> DelegateResult("NEEDS_HUMAN", "${status.cleanedText.take(MAX_TEXT)}\nNeeds a human: ${status.detail}")
                status.kind == StatusKind.FAILED -> DelegateResult("FAILED", "${status.cleanedText.take(MAX_TEXT)}\nReported failure: ${status.detail}")
                else -> DelegateResult("DONE", status.cleanedText.ifBlank { "Done (no text report)." }.take(MAX_TEXT))
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { agent.removeApprover(approver) }
                runCatching { agent.conversation.value.id?.value?.let { manager.destroy(it) } ?: agent.release() }
                Logger.i(LOG_TAG, "delegated run of ${profile.name} finished in ${System.currentTimeMillis() - startMs} ms")
            }
        }
    }

    private companion object {
        const val LOG_TAG = "AutonomyDelegate"
        const val MAX_CONCURRENT = 3
        const val MAX_STEPS = 40
        const val MAX_MS = 10 * 60_000L
        const val MAX_TOKENS = 300_000L
        const val MAX_TEXT = 6_000
        const val MAX_WAIT_MS = 120_000L
    }
}
