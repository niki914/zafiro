package com.niki914.zafiro.api.model

/**
 * 用户输入草稿：一段文本 + N 张图。
 *
 * 为什么不做成通用的 `List<Part>`：输入框的形状是固定的——只有一段文本、
 * 任意张图。用列表表达会丢掉这个不变量（列表可以有两段文本），
 * 消费方每处都要自己找文本部件、自己维持顺序，而收益是「将来可能有别的部件」
 * 这种尚未出现的需求。需要新增部件类型时再改这一处。
 *
 * 草稿属于 agent 的状态，不属于消费方，因此多个客户端
 * （Compose 输入框、宿主提交路径）改的是同一份文本，
 * 不会出现两份输入互相覆盖。
 *
 * ## 写入路径只有一条
 *
 * 全部修改经 `Agent.updateDraft`，它是**同步纯变换**。图片落盘是异步 I/O，
 * 不能塞进这次变换（会把 I/O 关进归约里），也不值当单开一个
 * `attachImage(uri)` 命令——那会让草稿有两个写入口，并且把
 * 「图片怎么进来」的知识搬到接口上。改为用 [DraftImage] 的两种形态表达进度：
 *
 * ```
 * // 消费方（选中图片后）：
 * agent.updateDraft { it.copy(images = it.images + DraftImage.Pending(uri)) }
 * // 实现侧（观察到 Pending 项）：
 * pass → 归约成 DraftImage.Ready(Attachment(path))
 * fail → 从草稿里移除该项（消费方从状态里看到它消失）
 * ```
 *
 * 这样 UI 免费得到「上传中」的占位，`stream()` 也不用等：
 * 实现负责在发起前把 Pending 处理完（等待或跳过）。
 *
 * ## 设计边界：草稿的落盘不在契约里
 *
 * 草稿文本的落盘仍由 app 侧完成（Room 的 `draft_text` 列，按按键节流写），
 * 草稿图片不进 Room，只在内存中保留、随会话切换清空。本类型只描述输入框的内容，
 * 不承载落盘口径。
 */
data class Draft(
    val text: String = "",

    /**
     * 待发送图片，顺序即展示顺序。按 URI / 落盘路径去重。
     * 消费方按 `Pending.uri` / `Ready.attachment.path` 定位与移除，不另设 id。
     */
    val images: List<DraftImage> = emptyList(),
)

/**
 * 草稿里的一张图片的两种状态。
 *
 * 消费方差异：
 * - [Pending]：尚未落盘。Compose 显示占位（可以直接用 URI 预览，或转圈）；
 *   `stream()` 发起前由实现处理。
 * - [Ready]：图片字节已写入 app 沙箱，可以发送。
 */
sealed interface DraftImage {

    /** 已选择、尚未落盘。消费方：Compose 选图后立即追加。 */
    data class Pending(val uri: String) : DraftImage

    /** 图片字节已写入 app 沙箱。消费方：发送、渲染缩略图（不进 Room）。 */
    data class Ready(val attachment: Attachment) : DraftImage
}
