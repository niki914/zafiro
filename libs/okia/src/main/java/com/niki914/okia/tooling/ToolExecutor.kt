package com.niki914.okia.tooling

import com.niki914.okia.message.ToolCallOutcome

/**
 * 执行一次工具调用。永不抛异常，总是产出工具结果。
 * 中断判定 = executor 内部状态：onInterrupt 从自身状态判断调用是否已运行。
 * 中断的资源清理是下游职责，库只提供回调时机（Hooks.beforeStop）。
 * Design source: kai PRD §4.5 ToolExecutor；okia 骨架对照基线。
 */
interface ToolExecutor {

    // 执行工具调用；永不抛异常，总是产出工具结果
    suspend fun execute(call: ToolCallContext): ToolCallOutcome

    // 中断判定：未运行 → Interrupted；可能已远程执行 → Unknown（永不重试）
    fun onInterrupt(call: ToolCallContext): ToolCallOutcome
}
