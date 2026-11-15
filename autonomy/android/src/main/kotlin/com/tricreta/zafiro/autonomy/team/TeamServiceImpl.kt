package com.tricreta.zafiro.autonomy.team

import com.niki914.zafiro.api.TeamResult
import com.niki914.zafiro.api.TeamService
import com.tricreta.zafiro.autonomy.core.BoardRules
import com.tricreta.zafiro.autonomy.core.Pacing
import com.tricreta.zafiro.autonomy.core.Quotas
import com.tricreta.zafiro.autonomy.core.Tiering
import com.tricreta.zafiro.autonomy.data.ActionLogEntity
import com.tricreta.zafiro.autonomy.data.AutonomyDao
import com.tricreta.zafiro.autonomy.data.BoardItemEntity
import com.tricreta.zafiro.autonomy.data.ExampleEntity
import com.tricreta.zafiro.autonomy.data.TierEntity
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/**
 * The shared board, action log (daily caps + approval gates), tiered autonomy and the owner's example library.
 * Every method returns plain JSON text the agent can read; failures are explicit, never silent.
 */
class TeamServiceImpl(
    private val dao: AutonomyDao,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val quotasOverride: (suspend (workspace: String) -> Quotas)? = null,
    private val random: () -> Double = { Random.nextDouble() },
    private val onSchedulesChanged: suspend () -> Unit = {},
) : TeamService {

    private suspend fun quotas(ws: String): Quotas = quotasOverride?.invoke(ws)
        ?: dao.workspace(ws)?.quotas?.takeIf { it.isNotBlank() }?.let { Quotas.parse(it) } ?: Quotas.DEFAULT

    override suspend fun run(tool: String, args: Map<String, String>): TeamResult = try {
        when (tool) {
            "board" -> board(args)
            "engage" -> engage(args)
            "examples" -> examples(args)
            "schedule" -> schedule(args)
            else -> TeamResult(false, "Unknown team tool '$tool'.")
        }
    } catch (e: IllegalArgumentException) {
        TeamResult(false, e.message ?: "Invalid arguments.")
    }

    private fun arg(a: Map<String, String>, k: String) = a[k]?.trim().orEmpty()
    private fun argL(a: Map<String, String>, k: String) = a[k]?.toDoubleOrNull()?.toLong()
    private fun need(a: Map<String, String>, k: String): String = arg(a, k).ifEmpty { throw IllegalArgumentException("'$k' is required.") }
    private fun day(): String = Instant.ofEpochMilli(nowMs()).atZone(zone()).toLocalDate().toString()

    // ---------------------------------------------------------------- board
    private suspend fun board(a: Map<String, String>): TeamResult {
        val ws = arg(a, "workspace")
        return when (need(a, "op")) {
            "add" -> {
                val depth = (argL(a, "depth") ?: 0L).toInt()
                if (!BoardRules.canDelegate(depth)) {
                    return TeamResult(false, "Delegation is limited to ${BoardRules.MAX_DEPTH} levels; do this work yourself.")
                }
                val now = nowMs()
                val id = dao.insertBoardItem(
                    BoardItemEntity(
                        workspaceId = ws, parentId = argL(a, "parent"), title = need(a, "title").take(200), detail = arg(a, "detail").take(4000),
                        ownerAgent = arg(a, "owner"), createdBy = arg(a, "by"), status = BoardRules.Status.TODO.name, result = "", depth = depth,
                        createdAtMs = now, updatedAtMs = now,
                    ),
                )
                TeamResult(true, JSONObject().put("id", id).put("status", "TODO").toString())
            }
            "next" -> {
                val owner = need(a, "owner")
                val item = dao.openBoardItems(ws).firstOrNull {
                    it.status == BoardRules.Status.TODO.name && it.ownerAgent.equals(owner, ignoreCase = true)
                } ?: return TeamResult(true, JSONObject().put("item", JSONObject.NULL).put("note", "Nothing waiting for $owner.").toString())
                dao.updateBoardItem(item.id, BoardRules.Status.DOING.name, item.result, "", nowMs())
                TeamResult(true, JSONObject().put("item", toJson(item.copy(status = "DOING"))).toString())
            }
            "update" -> {
                val id = argL(a, "id") ?: throw IllegalArgumentException("'id' is required.")
                val item = dao.boardItem(id) ?: return TeamResult(false, "No board item $id.")
                val to = BoardRules.parse(a["status"]) ?: throw IllegalArgumentException("'status' must be TODO, DOING, DONE, FAILED or BLOCKED.")
                val from = BoardRules.parse(item.status) ?: BoardRules.Status.TODO
                if (from != to && !BoardRules.canMove(from, to)) return TeamResult(false, "Item $id is $from and cannot move to $to.")
                if ((to == BoardRules.Status.DONE || to == BoardRules.Status.FAILED) && arg(a, "result").isEmpty()) {
                    throw IllegalArgumentException("A result is required when finishing an item: say what happened and where the output is.")
                }
                dao.updateBoardItem(id, to.name, arg(a, "result").take(4000).ifEmpty { item.result }, arg(a, "owner"), nowMs())
                TeamResult(true, JSONObject().put("id", id).put("status", to.name).toString())
            }
            "list" -> {
                val onlyOpen = a["open_only"]?.toBooleanStrictOrNull() ?: false
                val items = if (onlyOpen) dao.openBoardItems(ws) else dao.boardItems(ws, 60)
                TeamResult(true, JSONArray(items.map { toJson(it) }).toString())
            }
            "resume" -> {
                val n = dao.reopenStaleBoardItems(nowMs() - STALE_MS, nowMs())
                TeamResult(true, JSONObject().put("reopened", n).toString())
            }
            else -> TeamResult(false, "op must be add, next, update, list or resume.")
        }
    }

    private fun toJson(i: BoardItemEntity) = JSONObject().put("id", i.id).put("title", i.title).put("detail", i.detail).put("owner", i.ownerAgent)
        .put("by", i.createdBy).put("status", i.status).put("result", i.result).put("depth", i.depth).put("parent", i.parentId ?: JSONObject.NULL)

    // -------------------------------------------------------------- engage
    private suspend fun engage(a: Map<String, String>): TeamResult {
        val ws = arg(a, "workspace")
        return when (need(a, "op")) {
            "plan" -> {
                val action = need(a, "action").lowercase()
                val q = quotas(ws)
                val done = dao.countActions(ws, action, day())
                when (val v = q.check(action, done)) {
                    is Quotas.Verdict.Denied -> TeamResult(true, JSONObject().put("gate", "DENIED").put("reason", v.reason).toString())
                    is Quotas.Verdict.Allowed -> {
                        val t = dao.tier(ws, action)
                        val gate = Tiering.gate(action, a["own_thread"]?.toBooleanStrictOrNull() ?: false, Tiering.State(t?.streak ?: 0, t?.auto ?: false), a["serious"]?.toBooleanStrictOrNull() ?: false)
                        val now = nowMs()
                        val id = dao.insertAction(
                            ActionLogEntity(
                                workspaceId = ws, platform = arg(a, "platform"), action = action, target = arg(a, "target").take(300), text = arg(a, "text").take(4000),
                                dayKey = day(), status = if (gate == Tiering.Gate.AUTO) "APPROVED" else "PENDING", agent = arg(a, "by"), reason = "",
                                createdAtMs = now, updatedAtMs = now,
                            ),
                        )
                        TeamResult(
                            true,
                            JSONObject().put("id", id).put("gate", gate.name)
                                .put("remaining_today", if (v.remaining == Int.MAX_VALUE) JSONObject.NULL else v.remaining)
                                .put("next", if (gate == Tiering.Gate.AUTO) "Act now, then call engage done." else "Do NOT act. The owner must approve this id first.").toString(),
                        )
                    }
                }
            }
            "done", "fail" -> {
                val id = argL(a, "id") ?: throw IllegalArgumentException("'id' is required.")
                val row = dao.actionLog(id) ?: return TeamResult(false, "No action $id.")
                if (row.status == "PENDING") return TeamResult(false, "Action $id is waiting for owner approval; it cannot be recorded as done.")
                val ok = a["op"] == "done"
                dao.setActionStatus(id, if (ok) "DONE" else "FAILED", arg(a, "note").take(500), nowMs())
                TeamResult(true, JSONObject().put("id", id).put("status", if (ok) "DONE" else "FAILED").toString())
            }
            "approve", "reject" -> {
                val id = argL(a, "id") ?: throw IllegalArgumentException("'id' is required.")
                val row = dao.actionLog(id) ?: return TeamResult(false, "No action $id.")
                if (row.status != "PENDING") return TeamResult(false, "Action $id is ${row.status}, not waiting.")
                val approve = a["op"] == "approve"
                dao.setActionStatus(id, if (approve) "APPROVED" else "REJECTED", arg(a, "reason").take(500), nowMs())
                val t = dao.tier(row.workspaceId, row.action)
                val next = if (approve) Tiering.approved(Tiering.State(t?.streak ?: 0, t?.auto ?: false)) else Tiering.rejected(Tiering.State())
                dao.upsertTier(TierEntity(row.workspaceId, row.action, next.streak, next.auto))
                if (!approve && arg(a, "reason").isNotEmpty()) {
                    dao.insertExample(ExampleEntity(platform = row.platform, kind = row.action, text = row.text, verdict = "BAD", reason = arg(a, "reason").take(500), createdAtMs = nowMs()))
                } else if (approve) {
                    dao.insertExample(ExampleEntity(platform = row.platform, kind = row.action, text = row.text, verdict = "GOOD", reason = arg(a, "reason").take(500), createdAtMs = nowMs()))
                }
                TeamResult(true, JSONObject().put("id", id).put("status", if (approve) "APPROVED" else "REJECTED").put("approval_streak", next.streak).put("auto", next.auto).toString())
            }
            "pending" -> TeamResult(true, JSONArray(dao.actions(ws, 100).filter { it.status == "PENDING" || it.status == "APPROVED" }.map { actionJson(it) }).toString())
            "today" -> {
                val q = quotas(ws)
                val out = JSONObject()
                q.limits.forEach { (k, cap) -> out.put(k, JSONObject().put("done", dao.countActions(ws, k, day())).put("cap", cap)) }
                TeamResult(true, out.toString())
            }
            "pace" -> {
                val lo = ((a["min_s"]?.toDoubleOrNull() ?: 20.0) * 1000).toLong()
                val hi = ((a["max_s"]?.toDoubleOrNull() ?: 90.0) * 1000).toLong()
                TeamResult(true, JSONObject().put("wait_seconds", Pacing.delayMs(lo, hi, random()) / 1000).toString())
            }
            else -> TeamResult(false, "op must be plan, done, fail, approve, reject, pending, today or pace.")
        }
    }

    private fun actionJson(r: ActionLogEntity) = JSONObject().put("id", r.id).put("platform", r.platform).put("action", r.action).put("target", r.target)
        .put("text", r.text).put("status", r.status).put("agent", r.agent)

    // ------------------------------------------------------------- schedule
    /** Lets an agent manage its OWN scheduled tasks (tagged workspace "agent"); the owner's and workspace tasks are read-only to it. */
    private suspend fun schedule(a: Map<String, String>): TeamResult {
        return when (need(a, "op")) {
            "list" -> TeamResult(true, JSONArray(dao.tasks().sortedBy { it.name.lowercase() }.map {
                JSONObject().put("id", it.id).put("name", it.name).put("enabled", it.enabled).put("trigger", it.triggerType).put("time", it.timeOfDay ?: "")
                    .put("agent", it.agentId).put("owner", if (it.workspaceId == AGENT_TASKS) "agent" else it.workspaceId.ifEmpty { "owner" })
            }).toString())
            "create" -> {
                if (dao.tasks().count { it.workspaceId == AGENT_TASKS } >= MAX_AGENT_TASKS) return TeamResult(false, "Too many agent-made tasks ($MAX_AGENT_TASKS). Delete some first.")
                val trigger = need(a, "trigger").uppercase()
                val time = arg(a, "time")
                val interval = argL(a, "interval_minutes") ?: 0L
                when (trigger) {
                    "DAILY", "WEEKLY" -> if (!Regex("([01]\\d|2[0-3]):[0-5]\\d").matches(time)) return TeamResult(false, "time must be HH:mm (24 hour).")
                    "INTERVAL" -> if (interval < 15) return TeamResult(false, "interval_minutes must be at least 15.")
                    "ONCE" -> if (runCatching { java.time.LocalDateTime.parse(arg(a, "once_at")) }.isFailure) return TeamResult(false, "once_at must be like 2026-11-16T09:30 (device time zone).")
                    else -> return TeamResult(false, "trigger must be DAILY, WEEKLY, INTERVAL or ONCE.")
                }
                val agent = arg(a, "agent")
                if (agent.isNotEmpty() && dao.agent(agent) == null) return TeamResult(false, "No agent with id \"$agent\".")
                val days = arg(a, "days").split(',').mapNotNull { d -> java.time.DayOfWeek.entries.firstOrNull { it.name.startsWith(d.trim().uppercase().take(3)) && d.isNotBlank() } }.toSet()
                val now = nowMs()
                val id = "agent." + java.util.UUID.randomUUID().toString().take(8)
                val task = com.tricreta.zafiro.autonomy.data.TaskEntity(
                    id = id, name = need(a, "name").take(120), prompt = need(a, "prompt").take(8000), enabled = true, triggerType = trigger,
                    onceAt = arg(a, "once_at").ifEmpty { null }.takeIf { trigger == "ONCE" }, intervalMinutes = interval.takeIf { trigger == "INTERVAL" },
                    anchorMs = now.takeIf { trigger == "INTERVAL" }, timeOfDay = time.ifEmpty { null }, daysMask = if (days.isEmpty()) 0b1111111 else com.tricreta.zafiro.autonomy.data.daysToMask(days),
                    dayOfMonth = 1, zoneId = "", createdAtMs = now, updatedAtMs = now, misfireGraceMs = 6 * 3_600_000L, overlap = "SKIP",
                    maxSteps = (argL(a, "max_steps") ?: 60).toInt().coerceIn(5, 200), maxDurationMs = (argL(a, "max_minutes") ?: 30).coerceIn(2, 120) * 60_000L,
                    configId = "", approvalTimeoutMs = 120_000L, agentId = agent, workspaceId = AGENT_TASKS,
                )
                dao.upsertTask(task); dao.upsertCursor(com.tricreta.zafiro.autonomy.data.CursorEntity(id, now))
                onSchedulesChanged()
                TeamResult(true, JSONObject().put("id", id).put("note", "Scheduled. The owner can see and delete it under Manage and reset.").toString())
            }
            "enable", "disable", "delete" -> {
                val id = need(a, "id")
                val t = dao.task(id) ?: return TeamResult(false, "No task $id.")
                if (t.workspaceId != AGENT_TASKS) return TeamResult(false, "Only tasks made by agents can be changed here; the owner's and workspace tasks are managed by the owner.")
                when (a["op"]) {
                    "delete" -> { dao.deleteTask(id); dao.deleteCursor(id) }
                    else -> dao.setEnabled(id, a["op"] == "enable", nowMs())
                }
                onSchedulesChanged()
                TeamResult(true, JSONObject().put("id", id).put("done", a["op"]).toString())
            }
            else -> TeamResult(false, "op must be list, create, enable, disable or delete.")
        }
    }

    // ------------------------------------------------------------ examples
    private suspend fun examples(a: Map<String, String>): TeamResult = when (need(a, "op")) {
        "list" -> TeamResult(true, JSONArray(dao.examples(arg(a, "platform"), 30).map {
            JSONObject().put("id", it.id).put("verdict", it.verdict).put("kind", it.kind).put("text", it.text).put("reason", it.reason)
        }).toString())
        "add" -> {
            val v = need(a, "verdict").uppercase()
            if (v != "GOOD" && v != "BAD") throw IllegalArgumentException("'verdict' must be GOOD or BAD.")
            val id = dao.insertExample(ExampleEntity(platform = arg(a, "platform"), kind = arg(a, "kind"), text = need(a, "text").take(4000), verdict = v, reason = arg(a, "reason").take(500), createdAtMs = nowMs()))
            TeamResult(true, JSONObject().put("id", id).toString())
        }
        "correct" -> {
            val id = dao.insertExample(ExampleEntity(platform = arg(a, "platform"), kind = "correction", text = need(a, "text").take(4000), verdict = "BAD", reason = need(a, "reason").take(500), createdAtMs = nowMs()))
            TeamResult(
                true,
                JSONObject().put("example_id", id).put("next", "Now also: write the rule into the workspace rulebook with the files tool, and add a lesson with the memory tool. Both in this same turn.").toString(),
            )
        }
        "remove" -> {
            dao.deleteExample(argL(a, "id") ?: throw IllegalArgumentException("'id' is required."))
            TeamResult(true, "removed")
        }
        else -> TeamResult(false, "op must be list, add, correct or remove.")
    }

    private companion object {
        /** A DOING item untouched for this long belongs to a dead run. */
        const val STALE_MS = 20 * 60_000L
        const val AGENT_TASKS = "agent"
        const val MAX_AGENT_TASKS = 40
    }
}
