package com.niki914.zafiro.api.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 协程防抖调度契约。
 *
 * 适用于输入框键入防抖、草稿自动保存、搜索实时过滤等高频触发场景。
 */
interface Debouncer {
    /** 默认延迟毫秒数 */
    val defaultDelayMs: Long

    /** 当前是否存在处于等待或在途执行中的防抖任务 */
    val isPending: Boolean

    /**
     * 提交防抖动作。在 [delayMs] 窗口期内若再次触发，重置倒计时并覆盖待执行动作。
     */
    fun debounce(delayMs: Long = defaultDelayMs, action: suspend () -> Unit)

    /**
     * 取消当前挂起中的在途动作与计时器。
     */
    fun cancel()

    /**
     * 立即执行当前挂起中的在途动作（不等待剩余延迟时间），并清除挂起状态。
     * 若当前没有挂起的在途动作，则无操作。
     */
    fun flush()
}

/**
 * 创建标准协程防抖调度器。
 *
 * 核心机制：
 * 1. 随传入的 [scope] 生命周期自然取消，防止协程与上下文泄漏；
 * 2. 连续调用自动取消旧任务并重置计时；
 * 3. 支持 [flush] 在组件/页面退出前强制落地最新数据。
 */
fun Debouncer(
    scope: CoroutineScope,
    defaultDelayMs: Long = 300L,
): Debouncer = RealDebouncer(scope, defaultDelayMs)

/**
 * 便捷扩展函数：直接从 [CoroutineScope] 创建 [Debouncer]。
 */
fun CoroutineScope.debouncer(defaultDelayMs: Long = 300L): Debouncer =
    Debouncer(this, defaultDelayMs)

private class RealDebouncer(
    private val scope: CoroutineScope,
    override val defaultDelayMs: Long,
) : Debouncer {
    @Volatile
    private var job: Job? = null

    @Volatile
    private var pendingAction: (suspend () -> Unit)? = null

    override val isPending: Boolean
        get() = job?.isActive == true

    override fun debounce(delayMs: Long, action: suspend () -> Unit) {
        pendingAction = action
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            val toRun = pendingAction ?: return@launch
            pendingAction = null
            toRun()
        }
    }

    override fun cancel() {
        job?.cancel()
        job = null
        pendingAction = null
    }

    override fun flush() {
        val toRun = pendingAction ?: return
        cancel()
        job = scope.launch { toRun() }
    }
}
