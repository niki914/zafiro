package com.niki914.zafiro.app.ui.model

import kotlinx.coroutines.delay

/**
 * 流式文本节流器：上游 delta 即刻到达，但按自适应速度分帧放出，避免大块文本
 * 一次性砸进重组。速度策略沿用原 RevealTimeline：基速兜底、积压按
 * CATCH_UP_SECONDS 追平加速、上限封顶；两次放出的间隔超过 STALL_SNAP_MS
 * 直接追平（上游停顿/后台恢复期间积累的内容无观看价值）。
 *
 * 坐标是当前文本段的字符偏移：TextDelta.fullText 为段内全量（Mapper 在块边界
 * 重置累计），每个文本段开始前调用 [reset]。
 */
internal class TextPacer(
    private val delayFn: suspend (Long) -> Unit = ::delay,
) {
    /** 当前段已放出的字符数。 */
    internal var released: Int = 0
        private set

    private var lastFrameMs: Long = 0L

    /**
     * 把段内全量 [targetChars] 按速度放出，每次放出回调 [onRelease]
     * （参数 = 本帧放出区间 [from, to)），调用方据此从 fullText 切增量。
     */
    internal suspend fun pace(targetChars: Int, onRelease: suspend (Int, Int) -> Unit) {
        if (targetChars <= released) return
        var firstFrame = lastFrameMs == 0L
        while (released < targetChars) {
            val now = System.currentTimeMillis()
            if (!firstFrame && now - lastFrameMs > STALL_SNAP_MS) {
                val from = released
                released = targetChars
                onRelease(from, released)
                break
            }
            // 每轮至少延迟了 FRAME_MS（虚拟时间下墙钟不动，按下限推进防死循环）
            val elapsedS = if (firstFrame) {
                0f
            } else {
                maxOf((now - lastFrameMs) / 1000f, FRAME_MS / 1000f)
            }
            val step = advance(
                current = released.toFloat(),
                target = targetChars.toFloat(),
                elapsedSeconds = elapsedS,
            ).toInt()
            if (step <= 0) {
                delayFn(FRAME_MS)
                lastFrameMs = System.currentTimeMillis()
                firstFrame = false
                continue
            }
            val from = released
            released = (released + step).coerceAtMost(targetChars)
            onRelease(from, released)
            if (released < targetChars) {
                delayFn(FRAME_MS)
            }
            lastFrameMs = System.currentTimeMillis()
            firstFrame = false
        }
        lastFrameMs = System.currentTimeMillis()
    }

    /** 新回合归零。 */
    internal fun reset() {
        released = 0
        lastFrameMs = 0L
    }

    /** 取消/追平场景：不做节奏计算，直接把 released 推到 [value]。 */
    internal fun syncReleased(value: Int) {
        if (value > released) released = value
    }

    companion object {
        /**
         * 单帧步长：基速 [BASE_CHARS_PER_SECOND]，积压时按 [CATCH_UP_SECONDS] 追平
         * 加速，上限 [MAX_CHARS_PER_SECOND]。
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
        internal const val STALL_SNAP_MS = 250L
        internal const val FRAME_MS = 50L
    }
}
