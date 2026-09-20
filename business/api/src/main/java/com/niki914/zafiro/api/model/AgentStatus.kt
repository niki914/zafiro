package com.niki914.zafiro.api.model

/**
 * 粗粒度状态：阶段 + 结束方式 + 单行摘要。系统入口的读模型
 * （常驻通知、悬浮窗、MainActivity 的屏幕常亮）。
 *
 * 实现侧复用今天 `chat.AgentStatus` 的相位与预览逻辑，转入契约的
 * 同名类型后旧类型删除。`MutableStateFlow` 对相等值本来就跳过发射，
 * 因此不需要再叠一层去重投影。
 */
data class AgentStatus(
    /** 当前阶段。 */
    val phase: AgentPhase = AgentPhase.Idle,

    /** 上一轮的结束方式；仅在 [AgentPhase.Idle] 时有值。 */
    val outcome: TurnOutcome? = null,

    /**
     * 单行摘要，长度按通知一行截断（现有实现 120 字符，截断不切开代理对）。
     * 进行中：本回合 agent 的首句，尚未开口时用本回合提问；
     * 已完成：本回合 agent 的最后一句；失败 / 已停止 / 待命：null。
     */
    val preview: String? = null,
)

/** 对话的粗粒度阶段。 */
enum class AgentPhase {
    Idle,
    Generating,
    ToolRunning,

    /**
     * 等待工具执行确认。现有实现的枚举名是 `WaitingPermission`，
     * 改名以免与系统权限混淆。
     */
    WaitingApproval,
}

/** 回合的结束方式。 */
enum class TurnOutcome {
    Completed,
    Failed,

    /** 用户打断，或回合尾没有终态事件。 */
    Interrupted,
}
