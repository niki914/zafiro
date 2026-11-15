package com.niki914.zafiro.chat.agentic.routine

import com.niki914.zafiro.api.screen.ActResult
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.Screen
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.ScreenSnapshot
import com.niki914.zafiro.api.screen.SemanticToken
import com.niki914.zafiro.api.screen.ShowResult
import com.niki914.zafiro.api.screen.ShowTarget
import com.niki914.zafiro.api.screen.Target
import kotlinx.coroutines.delay

data class RoutineResult(
    val ok: Boolean,
    val routine: String,
    val stepsDone: Int,
    val totalSteps: Int,
    val failedStep: Int?,
    val reason: String,
    /** True when the screen differs from where the routine started. */
    val screenChanged: Boolean,
    /** What is on screen at the end, as short text lines. The agent verifies success from this, not from the step count. */
    val evidence: List<String>,
    val collected: List<String>,
)

/**
 * Runs a routine on a [Screen] with every action verified: the tree is re-read before each step (no stale tokens), a tap
 * must change the screen (retried once, then reported as failed instead of looping) and typed text must be visible.
 * One call replaces about thirty model steps.
 */
class RoutineEngine(
    private val screen: Screen,
    private val pollMs: Long = 600,
    private val settleMs: Long = 700,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private class StepFailed(val reason: String) : Exception(reason)

    suspend fun run(id: String, steps: List<RoutineStep>, params: Map<String, String>): RoutineResult {
        val startYaml = runCatching { screen.snapshot().yaml }.getOrDefault("")
        var done = 0
        val collected = LinkedHashSet<String>()
        for ((i, raw) in steps.withIndex()) {
            val step = raw.substitute(params)
            try {
                execute(step, collected)
                done++
            } catch (e: StepFailed) {
                if (step.optional) { done++; continue }
                return finish(id, false, done, steps.size, i + 1, "Step ${i + 1} (${step.op}${step.labels.firstOrNull()?.let { " \"$it\"" }.orEmpty()}): ${e.reason}", startYaml, collected)
            }
        }
        return finish(id, true, done, steps.size, null, "", startYaml, collected)
    }

    private suspend fun finish(id: String, ok: Boolean, done: Int, total: Int, failed: Int?, reason: String, startYaml: String, collected: Set<String>): RoutineResult {
        val snap = runCatching { screen.snapshot() }.getOrNull()
        return RoutineResult(ok, id, done, total, failed, reason, snap != null && snap.yaml != startYaml, snap?.let { texts(it).take(EVIDENCE_LINES) }.orEmpty(), collected.toList().take(MAX_COLLECTED))
    }

    private fun RoutineStep.substitute(p: Map<String, String>): RoutineStep {
        fun sub(s: String) = p.entries.fold(s) { acc, (k, v) -> acc.replace("{$k}", v) }
        return copy(text = sub(text), labels = labels.map(::sub), expectAny = expectAny.map(::sub), expectGone = expectGone.map(::sub))
    }

    private suspend fun execute(s: RoutineStep, collected: MutableSet<String>) {
        when (s.op) {
            "open_app" -> {
                when (val r = screen.show(ShowTarget.PackageName(s.packageName))) {
                    is ShowResult.Shown -> Unit
                    is ShowResult.Failed -> throw StepFailed("could not open ${s.packageName}: ${r.code}")
                    is ShowResult.Ambiguous -> throw StepFailed("ambiguous app ${s.packageName}")
                    is ShowResult.Unsupported -> throw StepFailed("this screen cannot open apps")
                }
                sleep(settleMs * 2)
                expect(s)
            }
            "tap" -> {
                val target = locate(s.labels, editable = false) ?: throw StepFailed("nothing on screen matches ${s.labels}")
                verifiedAct(target)
                expect(s)
            }
            "type" -> typeText(s)
            "wait" -> if (!waitAny(s.labels, s.timeoutS)) throw StepFailed("did not see ${s.labels} within ${s.timeoutS}s")
            "scroll" -> repeat(s.count.coerceIn(1, 20)) { scrollOnce(forward = s.text != "back"); sleep(settleMs) }
            "back" -> { act(ScreenAction.Key(KEY_BACK)); sleep(settleMs); expect(s) }
            "key" -> { act(ScreenAction.Key(s.code)); sleep(settleMs); expect(s) }
            "sleep" -> sleep(s.ms.coerceIn(0, 60_000))
            "collect" -> repeat(s.count.coerceIn(1, 15)) {
                texts(screen.snapshot()).filter { it.length >= MIN_COLLECT_LEN }.forEach { collected += it }
                scrollOnce(forward = true); sleep(settleMs)
            }
        }
    }

    private suspend fun typeText(s: RoutineStep) {
        val field = locate(s.labels, editable = true) ?: throw StepFailed("no text field found")
        repeat(2) { attempt ->
            val r = act(ScreenAction.SetText(field.target(), s.text))
            sleep(settleMs)
            val seen = screen.snapshot()
            val probe = s.text.trim().take(PROBE_LEN)
            if (probe.isEmpty() || seen.yaml.contains(probe)) { expect(s); return }
            if (attempt == 1) throw StepFailed("typed text did not appear in the field (${(r as? ActResult.Failed)?.code ?: "no error"})")
        }
    }

    /** Tap, then require the screen to change; one retry on a fresh tree; then a clear failure. */
    private suspend fun verifiedAct(first: Found) {
        var target = first
        repeat(2) { attempt ->
            val before = screen.snapshot().yaml
            val r = act(ScreenAction.Tap(target.target()))
            if (r is ActResult.Failed && attempt == 1) throw StepFailed("tap failed: ${r.code}")
            sleep(settleMs)
            val after = screen.snapshot()
            if (after.yaml != before) return
            if (attempt == 1) throw StepFailed("the screen did not change after tapping \"${target.label}\" (tried twice)")
            target = locate(listOf(target.label), editable = false) ?: throw StepFailed("\"${target.label}\" disappeared")
        }
    }

    private suspend fun expect(s: RoutineStep) {
        if (s.expectAny.isNotEmpty() && !waitAny(s.expectAny, s.timeoutS)) throw StepFailed("expected one of ${s.expectAny} but it never appeared")
        if (s.expectGone.isNotEmpty()) {
            val gone = poll(s.timeoutS) { snap -> s.expectGone.none { containsLabel(snap, it) } }
            if (!gone) throw StepFailed("${s.expectGone} is still on screen")
        }
    }

    private suspend fun waitAny(labels: List<String>, timeoutS: Int): Boolean = poll(timeoutS) { snap -> labels.any { containsLabel(snap, it) } }

    private suspend fun poll(timeoutS: Int, ok: (ScreenSnapshot) -> Boolean): Boolean {
        val tries = ((timeoutS.coerceIn(1, 60) * 1000L) / pollMs).toInt().coerceAtLeast(1)
        repeat(tries) {
            val snap = runCatching { screen.snapshot() }.getOrNull()
            if (snap != null && ok(snap)) return true
            sleep(pollMs)
        }
        return false
    }

    private suspend fun scrollOnce(forward: Boolean) {
        val snap = screen.snapshot()
        val node = flatten(snap.nodes).firstOrNull { it.isScrollable }
        val action = if (node != null) ScreenAction.Scroll(Target.Node(SemanticToken(snap.version, node.index)), forward)
        else if (forward) ScreenAction.Swipe(snap.width / 2, snap.height * 3 / 4, snap.width / 2, snap.height / 4, 350)
        else ScreenAction.Swipe(snap.width / 2, snap.height / 4, snap.width / 2, snap.height * 3 / 4, 350)
        act(action)
    }

    private suspend fun act(a: ScreenAction): ActResult = screen.act(a)

    private class Found(val label: String, val snapshot: ScreenSnapshot, val node: NodeInfo) {
        fun target(): Target = if (node.isClickable || node.isEditable) Target.Node(SemanticToken(snapshot.version, node.index))
        else Target.Point((node.bounds.left + node.bounds.right) / 2, (node.bounds.top + node.bounds.bottom) / 2)
    }

    /** Fresh tree every time. Label order is priority; among matches a clickable (or editable) node wins. */
    private suspend fun locate(labels: List<String>, editable: Boolean): Found? {
        val snap = screen.snapshot()
        val nodes = flatten(snap.nodes)
        if (editable) {
            val fields = nodes.filter { it.isEditable }
            for (l in labels) fields.firstOrNull { matches(it, l) }?.let { return Found(l, snap, it) }
            return fields.firstOrNull()?.let { Found(labels.firstOrNull().orEmpty(), snap, it) }
        }
        for (l in labels) {
            val hits = nodes.filter { matches(it, l) }
            val best = hits.firstOrNull { it.isClickable } ?: hits.firstOrNull()
            if (best != null) return Found(l, snap, best)
        }
        return null
    }

    companion object {
        const val KEY_BACK = 4
        private const val EVIDENCE_LINES = 25
        private const val MAX_COLLECTED = 60
        private const val MIN_COLLECT_LEN = 25
        private const val PROBE_LEN = 14

        fun flatten(nodes: List<NodeInfo>): List<NodeInfo> = nodes.flatMap { listOf(it) + flatten(it.children) }

        fun matches(n: NodeInfo, label: String): Boolean =
            n.text.contains(label, ignoreCase = true) || n.contentDesc.contains(label, ignoreCase = true)

        fun containsLabel(s: ScreenSnapshot, label: String): Boolean = flatten(s.nodes).any { matches(it, label) }

        fun texts(s: ScreenSnapshot): List<String> =
            flatten(s.nodes).map { it.text.ifBlank { it.contentDesc }.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
