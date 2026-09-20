package com.niki914.zafiro.api.model

/**
 * 一次工具调用的身份与静态信息（不含结果）。
 *
 * 消费方：
 * - Compose：按 [id] 原地更新同一次调用的行；用 [name] 映射展示名与参数预览
 *   （现有 `ToolPresentation.displayNameResOf` / `inputOf`）；[label] 作展示名回退。
 * - 宿主渲染：按 [id] 归并同一工具的 markdown 行（现有 `toolKey()` 的 `callId ?: name`）。
 * - 实现侧：用 [argumentsJson] 执行工具。
 *
 * 与现有 `ToolCallStatus` 的差异：
 * - `callId` 由可空改为必有 [id]：现有实现为「不提供 callId 的接入路径」保留了一套
 *   无名匹配兜底（`findToolBlockIndex` 的第二条分支），实现侧生成 id 后可以删掉。
 * - 去掉 `kind`（`ToolCallKind`）：生产代码只有 `Unknown` 一个值被写入，没有读取方。
 * - 去掉展示字段：`displayNameRes: Int` 是 Android 资源 id，不能进本模块（无 Android 依赖）；
 *   UI 侧继续用 [name] 自查展示名。
 */
data class ToolInvocation(
    /** 同一回合内唯一。消费方：Compose 行更新、宿主渲染行归并。 */
    val id: String,

    /** 工具名（模型侧名字，MCP 工具为 `mcp__server__tool`）。消费方：展示名/参数映射、实现侧路由。 */
    val name: String,

    /** 人类可读标签。消费方：Compose 展示名回退、宿主渲染行前缀。 */
    val label: String,

    /** 参数原文（JSON）。null = 参数仍在流式构建中（对应现有 `ToolPending` 事件）。消费方：实现侧执行、UI 复制。 */
    val argumentsJson: String? = null,
)

/**
 * 工具结果。
 *
 * 消费方：
 * - Compose：成功卡显示 [Succeeded.resultText] 与 [Succeeded.images]；失败卡显示
 *   [Failed.message] 与 [Failed.resultText]。
 * - 宿主渲染：只有文本进渲染帧（扫描确认：宿主渲染链路不消费图片）。
 *   因此 [Succeeded.images] 不影响宿主渲染。
 */
sealed interface ToolOutcome {
    /**
     * @property resultText 工具返回正文；null = 无正文（例如只有图片）。
     * @property images 工具返回的图片引用（view_image / screenshot 等），path 指向落盘文件。
     */
    data class Succeeded(
        val resultText: String? = null,
        val images: List<Attachment> = emptyList(),
    ) : ToolOutcome

    /**
     * @property message 失败原因（回喂模型与展示共用，现有实现两者同源）。
     * @property resultText 失败时仍可能有部分输出。
     */
    data class Failed(
        val message: String,
        val resultText: String? = null,
    ) : ToolOutcome
}
