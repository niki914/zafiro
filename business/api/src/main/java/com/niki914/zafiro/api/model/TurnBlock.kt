package com.niki914.zafiro.api.model

/**
 * 回合内的一个内容块。
 *
 * @property id 块身份，回合内唯一。消费方：Compose 列表 key 与展开态定位
 *   （现有实现用「回合 id + 块下标」拼接的字符串 key，有块 id 后不需要下标）；
 *   要求重新装配（含历史恢复）后保持稳定。
 */
sealed interface TurnBlock {
    val id: String

    /** 正文块。消费方：Compose 正文、宿主渲染帧文本。 */
    data class Text(
        override val id: String,
        val text: String,
    ) : TurnBlock

    /**
     * 思考块。
     * 消费方：Compose（展示、自动展开、结束后停止滚动跟随）；宿主渲染（折叠块）。
     */
    data class Thinking(
        override val id: String,
        val text: String,
        /** true = 该块已结束。消费方：Compose 判断是否继续跟随滚动。 */
        val isComplete: Boolean,
    ) : TurnBlock

    /**
     * 工具块。
     *
     * 消费方：Compose（展示名、参数预览、结果展开、图片预览、失败原因）。
     *
     * 没有 `state` 字段：现有 `HomeToolStatus.state`（Running / Succeeded /
     * Failed）可由 [outcome] 与 [invocation] 派生——`outcome == null`
     * 即未结算，`invocation.argumentsJson == null` 即参数未完整
     * （占位行、不可展开）。
     */
    data class Tool(
        override val id: String,
        val invocation: ToolInvocation,
        /** null = 未结算。 */
        val outcome: ToolOutcome? = null,
    ) : TurnBlock

    /** 回合失败卡。消费方：Compose 错误卡片（按 [code] 选文案分支）、宿主渲染。 */
    data class Failure(
        override val id: String,
        val message: String?,
        val code: TurnFailureCode? = null,
        val attempts: Int? = null,
    ) : TurnBlock

    /**
     * 瞬时重试提示（不落盘；下一个流事件到达即清除）。
     * 消费方：Compose 重试卡片、宿主渲染的重试行。
     */
    data class Retrying(
        override val id: String,
        val attempt: Int,
        val maxAttempts: Int,
        val delayMs: Long,
        val reason: String,
    ) : TurnBlock
}

/**
 * 回合失败分类。消费方按它选择文案分支，不解析 [TurnBlock.Failure.message]。
 *
 * 消费方：Compose 错误卡片（`ConfigRequired` / `RetryExhausted` /
 * `Auth` 等各有独立文案）、宿主渲染（无原文的错误由消费方给本地化文本）。
 *
 * 取值沿用现有 `LlmErrorCode`——它已被两处消费方穷举，值不变、只改位置。
 * `TurnConflict` 不再出现：已有活跃回合由 `stream()` 的返回值同步告知，
 * 不再折成错误卡。
 */
enum class TurnFailureCode {
    /** 缺少端点或模型：需要用户去设置页补齐。 */
    ConfigRequired,

    Auth,
    Quota,
    RateLimit,
    Overloaded,
    Transport,
    Parse,

    /** 重试耗尽，配合 [TurnBlock.Failure.attempts] 显示已尝试次数。 */
    RetryExhausted,

    /** 回合前后的钩子失败。 */
    HookFailed,

    /** 工具执行失败；回合是否继续由实现决定。 */
    ToolExecutionFailed,

    /** 空闲超时。 */
    IdleTimeout,
}
