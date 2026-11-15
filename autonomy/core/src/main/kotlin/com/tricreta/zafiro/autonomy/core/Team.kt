package com.tricreta.zafiro.autonomy.core

/** Shared task board rules: who may hand work to whom, and what a status change means. Pure. */
object BoardRules {
    const val MAX_DEPTH = 3

    enum class Status { TODO, DOING, DONE, FAILED, BLOCKED }

    /** An agent at [depth] (0 = started by a task or the owner) may delegate while the chain stays within [MAX_DEPTH]. */
    fun canDelegate(depth: Int): Boolean = depth in 0 until MAX_DEPTH

    fun parse(s: String?): Status? = Status.entries.firstOrNull { it.name.equals(s?.trim(), ignoreCase = true) }

    /** Allowed moves; finished items never reopen except by an explicit retry (FAILED/BLOCKED -> TODO). */
    fun canMove(from: Status, to: Status): Boolean = when (from) {
        Status.TODO -> to == Status.DOING || to == Status.BLOCKED || to == Status.DONE || to == Status.FAILED
        Status.DOING -> to != Status.TODO
        Status.BLOCKED -> to == Status.TODO || to == Status.DOING || to == Status.FAILED
        Status.FAILED -> to == Status.TODO
        Status.DONE -> false
    }

    /** True when every item is finished (DONE or FAILED): the Director may stop. */
    fun allFinished(statuses: Collection<Status>): Boolean = statuses.isNotEmpty() && statuses.all { it == Status.DONE || it == Status.FAILED }
}

/** Daily caps per action so the account behaves like a person. */
data class Quotas(val limits: Map<String, Int>) {
    sealed interface Verdict {
        data class Allowed(val remaining: Int) : Verdict
        data class Denied(val reason: String) : Verdict
    }

    fun check(action: String, doneToday: Int): Verdict {
        val cap = limits[action.lowercase()] ?: return Verdict.Allowed(Int.MAX_VALUE)
        return if (doneToday >= cap) Verdict.Denied("Daily cap for \"$action\" reached ($doneToday of $cap). Try again tomorrow.")
        else Verdict.Allowed(cap - doneToday - 1)
    }

    companion object {
        /** Deliberately modest. Owner-adjustable per workspace. */
        val DEFAULT = Quotas(
            mapOf("comment" to 15, "like" to 30, "restack" to 10, "reply" to 40, "post" to 3, "note" to 6, "follow" to 10, "dm" to 5),
        )

        fun parse(raw: String): Quotas = Quotas(
            raw.split(',', ';', '\n').mapNotNull {
                val (k, v) = it.split('=', ':').map { p -> p.trim() }.takeIf { p -> p.size == 2 } ?: return@mapNotNull null
                v.toIntOrNull()?.let { n -> k.lowercase() to n }
            }.toMap(),
        )
    }
}

/** Human-paced gaps between actions: never a fixed beat. */
object Pacing {
    fun delayMs(minMs: Long, maxMs: Long, random: Double): Long {
        val lo = minOf(minMs, maxMs).coerceAtLeast(0)
        val hi = maxOf(minMs, maxMs).coerceAtLeast(lo)
        return lo + ((hi - lo) * random.coerceIn(0.0, 1.0)).toLong()
    }
}

/** Tiered autonomy: ten approvals in a row promote an action type to automatic, one rejection demotes it. */
object Tiering {
    const val PROMOTE_AFTER = 10

    data class State(val streak: Int = 0, val auto: Boolean = false)

    fun approved(s: State): State {
        val streak = s.streak + 1
        return State(streak, s.auto || streak >= PROMOTE_AFTER)
    }

    fun rejected(@Suppress("UNUSED_PARAMETER") s: State): State = State(0, false)

    enum class Gate { AUTO, NEEDS_APPROVAL }

    /** Own-thread replies and likes are automatic from day one; everything else waits for approval until promoted. */
    fun gate(action: String, ownThread: Boolean, s: State): Gate {
        val a = action.lowercase()
        if (ownThread && (a == "reply" || a == "like")) return Gate.AUTO
        return if (s.auto) Gate.AUTO else Gate.NEEDS_APPROVAL
    }
}
