package com.niki914.zafiro.app.ui.content.reveal

import androidx.compose.runtime.withFrameNanos
import com.niki914.zafiro.app.ui.content.reveal.RevealTimeline.Companion.BASE_CHARS_PER_SECOND
import com.niki914.zafiro.app.ui.content.reveal.RevealTimeline.Companion.CATCH_UP_SECONDS
import com.niki914.zafiro.app.ui.content.reveal.RevealTimeline.Companion.MAX_CHARS_PER_SECOND
import com.niki914.zafiro.app.ui.content.reveal.RevealTimeline.Companion.STALL_SNAP_SECONDS
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 流式回答的打字进度时钟。
 *
 * 进度只有一个坐标：整条回答源文本的字符偏移（[revealedChars]）。每个参与显现的
 * 文本块用自己的源文本 startOffset 换算本地进度，块与块之间天然按源文本顺序衔接，
 * 不需要排队协议；块增删、重挂载都只是重新读一次进度。
 *
 * 进度是普通字段，帧间推进不写 Compose State，不触发重组与重排版；仅通过
 * [RevealNode.onRevealChanged] 让挂载中的节点重绘，显现跨行时由节点自行请求重测。
 */
class RevealTimeline {
    /** 当前源文本已确定的最大末偏移；各文本块按自身区间单调合并。 */
    @Volatile
    var targetChars: Int = 0
        private set

    /** 已显现到的源文本偏移。仅帧循环写入。 */
    @Volatile
    var revealedChars: Float = 0f
        private set

    val hasBacklog: Boolean
        get() = revealedChars < targetChars

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val nodes = mutableListOf<RevealNode>()

    /**
     * 合并一个文本块的源文本末偏移（startOffset + 块长）。
     * 取单调 max：多块回合（前置文本 + 工具 + 后续文本）里各块分别上报，
     * target 不会回缩——回缩会让已打出的字消失重打。
     */
    fun mergeTarget(sourceEnd: Int) {
        if (sourceEnd > targetChars) {
            targetChars = sourceEnd
            wake()
        }
    }

    /** 新回合 / 重新生成时归零，回答从头开始打字。 */
    fun reset() {
        revealedChars = 0f
        targetChars = 0
        wake()
    }

    internal fun attach(node: RevealNode) {
        nodes += node
        wake()
    }

    internal fun detach(node: RevealNode) {
        nodes.remove(node)
    }

    /**
     * 帧循环。有积压时按自适应速度推进；无积压时挂起等待唤醒
     * （文本增长或新节点挂载都会唤醒）。
     *
     * 页面退到后台时帧钟停摆；恢复后若与上一帧间隔超过 [STALL_SNAP_SECONDS]，
     * 直接追平目标不补播——后台期间 Runtime 继续追加的内容没有观看价值。
     */
    suspend fun run() {
        var previousFrameNanos = 0L
        while (currentCoroutineContext().isActive) {
            if (!hasBacklog) {
                previousFrameNanos = 0L
                wakeups.receive()
                continue
            }
            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                0f
            } else {
                (frameNanos - previousFrameNanos) / NANOS_PER_SECOND
            }
            previousFrameNanos = frameNanos
            if (!hasBacklog) continue

            revealedChars = if (elapsedSeconds > STALL_SNAP_SECONDS) {
                targetChars.toFloat()
            } else {
                advance(
                    current = revealedChars,
                    target = targetChars.toFloat(),
                    elapsedSeconds = elapsedSeconds,
                )
            }
            val snapshot = nodes.toList()
            for (node in snapshot) node.onRevealChanged()
        }
    }

    private fun wake() {
        wakeups.trySend(Unit)
    }

    companion object {
        /**
         * 单帧步长：基速 [BASE_CHARS_PER_SECOND]，积压时按 [CATCH_UP_SECONDS] 追平
         * 加速，上限 [MAX_CHARS_PER_SECOND]。模型快它就快，永不滞后。
         */
        internal fun advance(
            current: Float,
            target: Float,
            elapsedSeconds: Float,
        ): Float {
            if (current >= target) return target
            val backlog = target - current
            val speed = maxOf(
                BASE_CHARS_PER_SECOND,
                backlog / CATCH_UP_SECONDS,
            ).coerceAtMost(MAX_CHARS_PER_SECOND)
            val step = speed * elapsedSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
            return (current + step).coerceAtMost(target)
        }

        internal const val BASE_CHARS_PER_SECOND = 36f
        internal const val MAX_CHARS_PER_SECOND = 240f
        internal const val CATCH_UP_SECONDS = 0.20f
        internal const val MAX_FRAME_SECONDS = 0.05f
        internal const val STALL_SNAP_SECONDS = 0.25f
        private const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
