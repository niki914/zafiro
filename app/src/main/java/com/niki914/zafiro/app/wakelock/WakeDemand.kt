package com.niki914.zafiro.app.wakelock

/**
 * 唤醒锁需求方。每个来源只声明「我现在需要什么」，不直接申请锁。
 *
 * CPU 防休眠是所有需求的共同诉求，因此无需区分；屏幕常亮只有前台 Agent 回答才需要，
 * 外部 MCP 客户端调用时用户通常不在看屏幕，只防 CPU 休眠。
 */
enum class WakeDemand {
    /** 前台或后台有 Agent 会话处于运行态，且全局常亮开关已开启。 */
    AgentRunning,

    /** Zafiro 作为 MCP 服务对外暴露且处于 Running，且 MCP 页常亮开关已开启。 */
    McpServing,
}

/** 该需求是否要求屏幕保持常亮。CPU 锁不在此列：任一需求存在即需要。 */
val WakeDemand.needsScreenOn: Boolean
    get() = this == WakeDemand.AgentRunning
