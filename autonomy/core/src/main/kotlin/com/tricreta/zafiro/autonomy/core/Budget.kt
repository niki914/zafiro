package com.tricreta.zafiro.autonomy.core

/**
 * Hard limits for one run. Autonomy is bounded: breaching any limit stops the run with LIMIT_REACHED and a report.
 * A null limit means "unlimited" for that dimension (the UI defaults all of them to finite values).
 */
data class Budget(
    val maxSteps: Int? = DEFAULT_MAX_STEPS,
    val maxDurationMs: Long? = DEFAULT_MAX_DURATION_MS,
    val maxTokens: Long? = DEFAULT_MAX_TOKENS,
) {
    companion object {
        const val DEFAULT_MAX_STEPS = 60
        const val DEFAULT_MAX_DURATION_MS = 15 * 60 * 1000L
        const val DEFAULT_MAX_TOKENS = 400_000L
    }
}

enum class Breach { STEPS, DURATION, TOKENS }

/** Counts usage against a [Budget]. Time is injected so tests are deterministic; thread-safe. */
class BudgetTracker(private val budget: Budget, private val startedAtMs: Long) {
    private var steps = 0
    private var tokens = 0L

    @Synchronized
    fun recordStep() {
        steps++
    }

    /** Set the absolute step count (the Android layer derives it from the conversation's tool blocks). Never decreases. */
    @Synchronized
    fun setSteps(total: Int) {
        if (total > steps) steps = total
    }

    @Synchronized
    fun recordTokens(input: Long, output: Long) {
        tokens += input + output
    }

    /** Set the absolute token total (the Android layer reads it from the agent's cumulative usage). Never decreases. */
    @Synchronized
    fun setTokens(total: Long) {
        if (total > tokens) tokens = total
    }

    @Synchronized
    fun stepsUsed(): Int = steps

    @Synchronized
    fun tokensUsed(): Long = tokens

    /** First exceeded limit, or null. Steps are checked as "about to exceed": the (maxSteps+1)th step is refused. */
    @Synchronized
    fun check(nowMs: Long): Breach? {
        budget.maxDurationMs?.let { if (nowMs - startedAtMs >= it) return Breach.DURATION }
        budget.maxSteps?.let { if (steps >= it) return Breach.STEPS }
        budget.maxTokens?.let { if (tokens >= it) return Breach.TOKENS }
        return null
    }

    /** Remaining wall-clock budget, for sizing the wake lock. Null = unlimited. */
    fun remainingMs(nowMs: Long): Long? = budget.maxDurationMs?.let { (it - (nowMs - startedAtMs)).coerceAtLeast(0) }
}
