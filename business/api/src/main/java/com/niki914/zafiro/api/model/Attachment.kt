package com.niki914.zafiro.api.model

/**
 * 已落盘的图片引用。
 *
 * 消费方：
 * - Compose：按 [path] 解码显示（用户输入图片条、工具结果图片卡、历史恢复后的图片）
 * - 历史恢复：路径从落盘的协议消息里读回（字节在 app 沙箱，重启不丢）
 * - 实现侧：作为协议内容发送（[mimeType] 决定编码声明）
 *
 * 与现有 `HomeChatImage` 的差异：去掉 `id`——现有 UI 用它做列表 key
 * （历史恢复用 `path.hashCode()`，新 ingest 用 UUID），
 * 同一个东西两套口径。key 是消费方的派生值，不进契约。
 * 现有实现把 MIME 硬编码为 `image/jpeg`（ingest 管线统一转码），
 * 本字段保留是因为工具返回的图片不一定经过同一条管线。
 *
 * 写入路径：消费方经 `Agent.updateDraft` 追加 `DraftImage.Pending`，
 * 实现侧落盘后归约成 `DraftImage.Ready`，不设独立的图片导入命令。
 */
data class Attachment(
    /** 落盘绝对路径。消费方：Compose 解码、历史恢复、实现侧回读。 */
    val path: String,

    /** MIME 类型；null = 由消费方按扩展名推断。 */
    val mimeType: String? = null,
)
