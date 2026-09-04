package com.niki914.okia.message

import kotlinx.serialization.Serializable

/**
 * 一次工具调用的终态结果，工具块 UI 终态与持久化消息共用同一类型，
 * 无状态映射，中断语义在持久化恢复后可读。
 * Success / Failure / Intercepted 是正常结果；Interrupted / Unknown 覆盖
 * 取消回合，永不重试。Provider 编码的 isError 由 outcome 派生。
 * Design source: okia 骨架 ToolCallOutcome，删除 Blocked（审批拒绝由下游
 * hook 泛化为 Intercepted / Failure），新增 Intercepted（hook 拦截 ≠ 工具失败）。
 */
@Serializable
sealed interface ToolCallOutcome {

    /**
     * 工具成功，产出结果负载。内容为任意文本，不一定是 JSON。
     * image：工具返回的图片（view_image / MCP 图片等），path 引用。
     */
    @Serializable
    data class Success(val content: String, val image: ContentBlock.Image? = null) : ToolCallOutcome

    /** 工具已运行但失败。 */
    @Serializable
    data class Failure(val message: String, val content: String? = null) : ToolCallOutcome

    /** hook 拦截结果（审批拒绝、缓存命中、成功模拟），非工具失败。
     * content 在拦截产生结果负载时携带（如缓存命中内容、模拟成功输出）；
     * isError 供 Provider 派生错误位（审批拒绝 = true，缓存命中 / 模拟 = false）。 */
    @Serializable
    data class Intercepted(
        val reason: String,
        val content: String? = null,
        val isError: Boolean = false
    ) : ToolCallOutcome

    /** 工具执行前或执行中被中断；content 在存在部分输出时携带。 */
    @Serializable
    data class Interrupted(val content: String? = null) : ToolCallOutcome

    /** 执行状态未知，如远程调用可能在取消前已运行。永不重试。 */
    @Serializable
    data class Unknown(val message: String, val content: String? = null) : ToolCallOutcome
}
