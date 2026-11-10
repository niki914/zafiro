package com.tricreta.zafiro.autonomy.run

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.niki914.logging.Logger
import com.tricreta.zafiro.api.Approver
import com.tricreta.zafiro.api.model.ApprovalDecision
import com.tricreta.zafiro.api.model.ApprovalRequest
import com.tricreta.zafiro.autonomy.AutonomyGraph
import com.tricreta.zafiro.autonomy.AutonomyNotifier
import com.tricreta.zafiro.autonomy.data.AutonomyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Rendezvous between the notification buttons and the suspended approval request. */
object ApprovalBroker {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<ApprovalDecision>>()

    fun register(id: String): CompletableDeferred<ApprovalDecision> =
        CompletableDeferred<ApprovalDecision>().also { pending[id] = it }

    fun resolve(id: String, decision: ApprovalDecision): Boolean = pending.remove(id)?.complete(decision) == true

    fun forget(id: String) {
        pending.remove(id)
    }
}

/**
 * Approver attached to the task agent of ONE scheduled run. Upstream only wires approvers to the main chat agent, so
 * without this a tool guarded by a CONFIRM rule would be blocked ("no approver") in every unattended run.
 * Unanswered requests are DENIED after [timeoutMs] — silence never grants anything (SECURITY.md §2).
 */
class ScheduledRunApprover(
    private val runId: Long,
    private val taskName: String,
    private val timeoutMs: Long,
    private val repo: AutonomyRepository,
    private val notifier: AutonomyNotifier,
) : Approver {

    override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
        val requestId = UUID.randomUUID().toString()
        val detail = describe(request)
        val deferred = ApprovalBroker.register(requestId)
        repo.event(runId, "approval", "requested: $detail")
        notifier.postApproval(requestId, taskName, detail)
        try {
            val decision = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ApprovalDecision.Deny
            withContext(NonCancellable) {
                repo.event(runId, "approval", "decision: $decision" + if (decision == ApprovalDecision.Deny) " (denied or timed out)" else "")
            }
            return decision
        } finally {
            ApprovalBroker.forget(requestId)
            notifier.cancelApproval(requestId)
        }
    }

    private fun describe(request: ApprovalRequest): String = when (request) {
        is ApprovalRequest.ToolExecution ->
            "Tool '${request.toolName}' wants to run: ${request.command.take(300)} (rule: ${request.ruleName})"
        is ApprovalRequest.ScreenControlConsent -> "The agent wants to control the screen."
    }
}

class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val decision = if (intent.action == ACTION_ALLOW) ApprovalDecision.Allow else ApprovalDecision.Deny
        val resolved = ApprovalBroker.resolve(id, decision)
        Logger.i(LOG_TAG, "approval $decision resolved=$resolved")
        AutonomyGraph.get(context).notifier.cancelApproval(id)
    }

    companion object {
        const val ACTION_ALLOW = "com.tricreta.zafiro.autonomy.APPROVAL_ALLOW"
        const val ACTION_DENY = "com.tricreta.zafiro.autonomy.APPROVAL_DENY"
        const val EXTRA_REQUEST_ID = "request_id"
        private const val LOG_TAG = "AutonomyApproval"
    }
}
