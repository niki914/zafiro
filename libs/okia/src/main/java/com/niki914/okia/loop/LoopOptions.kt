package com.niki914.okia.loop

import com.niki914.okia.error.RetryPolicy

/**
 * 回合级行为选项。每次 send 声明，区别于连接配置。
 * Design source: kai PRD §4.4 / §4.7。
 */
data class LoopOptions(
    val segmentFailurePolicy: SegmentFailurePolicy = SegmentFailurePolicy.Discard,
    val turnRetryPolicy: RetryPolicy? = null
)

/**
 * 段失败时部分产出的处理。作用于段内部；loop 拥有边界，host 无感知。
 * Design source: kai PRD §4.4 段原子性。
 */
enum class SegmentFailurePolicy {
    Discard,
    Commit
}
