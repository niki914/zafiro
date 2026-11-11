package com.tricreta.zafiro.autonomy.core

/**
 * Lifecycle of one execution of a task.
 *
 *  QUEUED → STARTING → RUNNING → SUCCEEDED | FAILED | CANCELLED | LIMIT_REACHED | NEEDS_HUMAN | INTERRUPTED
 *  RUNNING ⇄ PAUSED (user pause, or waiting for an approval)
 *  MISSED is a terminal record created directly by the reconciler (it never runs).
 *
 * INTERRUPTED = the process died or the OS killed us mid-run; found by the heartbeat check on the next reconcile.
 */
enum class RunState(val terminal: Boolean) {
    QUEUED(false),
    STARTING(false),
    RUNNING(false),
    PAUSED(false),
    SUCCEEDED(true),
    FAILED(true),
    CANCELLED(true),
    LIMIT_REACHED(true),
    NEEDS_HUMAN(true),
    INTERRUPTED(true),
    MISSED(true);

    val active: Boolean get() = !terminal
}

object RunStateMachine {
    private val allowed: Map<RunState, Set<RunState>> = mapOf(
        RunState.QUEUED to setOf(RunState.STARTING, RunState.CANCELLED, RunState.FAILED),
        RunState.STARTING to setOf(RunState.RUNNING, RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED),
        RunState.RUNNING to setOf(
            RunState.PAUSED, RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED,
            RunState.LIMIT_REACHED, RunState.NEEDS_HUMAN, RunState.INTERRUPTED,
        ),
        RunState.PAUSED to setOf(
            RunState.RUNNING, RunState.CANCELLED, RunState.FAILED, RunState.LIMIT_REACHED,
            RunState.NEEDS_HUMAN, RunState.INTERRUPTED,
        ),
    )

    fun canTransition(from: RunState, to: RunState): Boolean = allowed[from]?.contains(to) == true

    /** Throws on an illegal move so a bug shows up in tests instead of corrupting history. */
    fun require(from: RunState, to: RunState) {
        check(canTransition(from, to)) { "illegal run transition $from -> $to" }
    }
}
