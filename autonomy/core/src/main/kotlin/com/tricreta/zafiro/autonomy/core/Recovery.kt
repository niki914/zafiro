package com.tricreta.zafiro.autonomy.core

/** What the agent says about its own run, on the last line of its report. */
enum class StatusKind { DONE, NEEDS_HUMAN, FAILED, MISSING }

data class AgentStatus(val kind: StatusKind, val detail: String, val cleanedText: String)

/**
 * The agent ends its report with one machine-readable line. Unattended runs need a verdict the code can act on; free text
 * alone cannot tell "finished" from "gave up". A missing line is treated as DONE so older prompts and chatty models keep working.
 */
object StatusProtocol {
    val INSTRUCTION: String =
        "End your final report with exactly one last line: `STATUS: DONE` when the goal is achieved, " +
            "`STATUS: NEEDS_HUMAN - <what you need>` when only a human can continue (login, CAPTCHA, consent, payment, a decision), " +
            "or `STATUS: FAILED - <why>` when you could not achieve the goal."

    private val line = Regex("""(?im)^\s*[`*]*STATUS:\s*(DONE|NEEDS_HUMAN|FAILED)\b[\s:\-–—]*(.*?)[`*]*\s*$""")

    fun parse(text: String?): AgentStatus {
        if (text.isNullOrBlank()) return AgentStatus(StatusKind.MISSING, "", "")
        val match = line.findAll(text).lastOrNull() ?: return AgentStatus(StatusKind.MISSING, "", text.trim())
        val kind = StatusKind.valueOf(match.groupValues[1].uppercase())
        val cleaned = text.removeRange(match.range).trim()
        return AgentStatus(kind, match.groupValues[2].trim(), cleaned)
    }
}

enum class FailureClass {
    /** Temporary provider/network trouble: waiting and continuing can help. */
    TRANSIENT,

    /** Credentials, credits or configuration: retrying cannot help, a person must act. */
    NEEDS_HUMAN,

    /** Anything else: do not loop on it. */
    FATAL,
}

object FailureClassifier {
    fun classify(code: String?): FailureClass = when (code) {
        "RateLimit", "Overloaded", "Transport", "RetryExhausted", "IdleTimeout", "OutputTruncated", "Parse" -> FailureClass.TRANSIENT
        "Auth", "Quota", "ConfigRequired" -> FailureClass.NEEDS_HUMAN
        else -> FailureClass.FATAL
    }

    fun humanReason(code: String?): String = when (code) {
        "Auth" -> "the model provider rejected the API key"
        "Quota" -> "the model provider reports no credits or quota left"
        "ConfigRequired" -> "no model is configured"
        else -> "an unrecoverable error ($code)"
    }
}

enum class TurnEnd { COMPLETED, FAILED, STOPPED }

data class TurnReport(
    val end: TurnEnd,
    val failureCode: String? = null,
    val lastText: String? = null,
    /** Set when the budget tripped; the planner just maps it to LIMIT_REACHED. */
    val breach: Breach? = null,
)

sealed interface Decision {
    data class Finish(val state: RunState, val summary: String?, val error: String?) : Decision

    /** Send [prompt] as the next turn in the SAME conversation (context is kept, finished work is not redone) after [delayMs]. */
    data class Continue(val prompt: String, val delayMs: Long, val note: String) : Decision

    data class Verify(val prompt: String) : Decision
}

data class RecoveryConfig(
    /** Extra turns allowed after the first: failures retried and unmet success criteria re-attempted. 0 = never recover. */
    val recoveries: Int = 2,
    val successCriteria: String = "",
    val baseBackoffMs: Long = 20_000,
    val maxBackoffMs: Long = 5 * 60_000,
)

/**
 * Decides what happens after each agent turn: finish, continue after a failure, or verify the outcome.
 * Pure state machine (no clock, no Android) so every branch is unit-tested. One instance per run.
 */
class RecoveryPlanner(private val cfg: RecoveryConfig) {
    private var recoveriesUsed = 0
    private var verifying = false
    private var verifyTurns = 0

    val recoveriesSoFar: Int get() = recoveriesUsed

    fun backoffMs(attempt: Int): Long {
        var d = cfg.baseBackoffMs
        repeat((attempt - 1).coerceAtLeast(0)) { d = (d * 2).coerceAtMost(cfg.maxBackoffMs) }
        return d.coerceAtMost(cfg.maxBackoffMs)
    }

    fun onTurnEnded(r: TurnReport): Decision {
        r.breach?.let {
            return Decision.Finish(RunState.LIMIT_REACHED, r.lastText, "Stopped: ${describe(it)}.")
        }
        if (r.end == TurnEnd.STOPPED) {
            return Decision.Finish(RunState.CANCELLED, r.lastText, "The run was stopped before it finished.")
        }
        if (r.end == TurnEnd.FAILED) {
            verifying = false
            return when (FailureClassifier.classify(r.failureCode)) {
                FailureClass.NEEDS_HUMAN ->
                    Decision.Finish(RunState.NEEDS_HUMAN, r.lastText, "Needs you: ${FailureClassifier.humanReason(r.failureCode)}.")
                FailureClass.FATAL ->
                    Decision.Finish(RunState.FAILED, r.lastText, "The agent failed: ${r.failureCode ?: "unknown"}.")
                FailureClass.TRANSIENT ->
                    if (recoveriesUsed < cfg.recoveries) {
                        recoveriesUsed++
                        Decision.Continue(
                            prompt = "Your previous step was interrupted by a temporary error (${r.failureCode}). " +
                                "Continue from where you left off. Do not repeat actions you already completed. " + StatusProtocol.INSTRUCTION,
                            delayMs = backoffMs(recoveriesUsed),
                            note = "temporary error ${r.failureCode}; retry $recoveriesUsed of ${cfg.recoveries}",
                        )
                    } else {
                        Decision.Finish(
                            RunState.FAILED, r.lastText,
                            "The agent failed: ${r.failureCode} (gave up after ${cfg.recoveries} recovery attempt(s)).",
                        )
                    }
            }
        }

        // COMPLETED
        val status = StatusProtocol.parse(r.lastText)
        if (verifying) return afterVerification(status, r)

        return when (status.kind) {
            StatusKind.NEEDS_HUMAN ->
                Decision.Finish(RunState.NEEDS_HUMAN, status.cleanedText, "Needs you: ${status.detail.ifBlank { "the agent asked for a human" }}.")
            StatusKind.FAILED ->
                Decision.Finish(RunState.FAILED, status.cleanedText, "The agent reported failure: ${status.detail.ifBlank { "no reason given" }}.")
            StatusKind.DONE, StatusKind.MISSING ->
                if (cfg.successCriteria.isBlank()) {
                    Decision.Finish(RunState.SUCCEEDED, status.cleanedText.ifBlank { "Completed with no text report." }, null)
                } else {
                    verifying = true
                    verifyTurns++
                    Decision.Verify(verifyPrompt())
                }
        }
    }

    private fun afterVerification(status: AgentStatus, r: TurnReport): Decision {
        verifying = false
        val text = r.lastText.orEmpty()
        val verified = Regex("""(?im)^\s*[`*]*VERIFIED\b""").containsMatchIn(text)
        val incomplete = Regex("""(?im)^\s*[`*]*INCOMPLETE\b[\s:\-–—]*(.*)$""").findAll(text).lastOrNull()
        if (verified && incomplete == null) {
            return Decision.Finish(RunState.SUCCEEDED, status.cleanedText.ifBlank { text.trim() }, null)
        }
        val why = incomplete?.groupValues?.get(1)?.trim().orEmpty().ifBlank { text.trim().take(300) }
        return if (recoveriesUsed < cfg.recoveries) {
            recoveriesUsed++
            Decision.Continue(
                prompt = "The success criteria are not yet met: $why. Fix this now, then give your final report. " + StatusProtocol.INSTRUCTION,
                delayMs = 0,
                note = "success criteria not met; retry $recoveriesUsed of ${cfg.recoveries}",
            )
        } else {
            Decision.Finish(RunState.FAILED, status.cleanedText.ifBlank { text.trim() }, "Success criteria not met: $why")
        }
    }

    private fun verifyPrompt(): String =
        "Verify your work against these success criteria by checking the real state with your tools, not by recalling what you did:\n" +
            cfg.successCriteria.trim() + "\n" +
            "Reply with exactly `VERIFIED` on its own line if every criterion is met, or `INCOMPLETE - <what is missing>` if not."

    private fun describe(b: Breach): String = when (b) {
        Breach.STEPS -> "the step limit was reached"
        Breach.DURATION -> "the time limit was reached"
        Breach.TOKENS -> "the token limit was reached"
    }
}
