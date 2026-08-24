package com.niki914.zafiro.mod.feat.hyper

/**
 * 单次流式渲染会话状态：记录当前 turnId、dialogId 和已累计文本，用于计算增量 delta。
 *
 * firstChunkReported/finalizedReported 只承担事件去重职责：首帧可能为空，不能仅依赖
 * render(isFirst/isFinal) 保证首个有效 delta 与 final 事件只上报一次。
 */
data class XiaoaiRenderSession(
    val turnId: Long,
    val dialogId: String,
    var renderedText: String = "",
    var firstChunkReported: Boolean = false,
    var finalizedReported: Boolean = false
)
