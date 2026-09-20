package com.niki914.zafiro.api

import com.niki914.zafiro.api.model.Conversation
import com.niki914.zafiro.api.model.ConversationId
import com.niki914.zafiro.api.model.Draft
import kotlinx.coroutines.flow.StateFlow

/**
 * 会话的内容与执行面：输入草稿、回合执行、会话切换。消费方：Compose 对话页、
 * 宿主（经 `AgentRuntimeService`）。
 *
 * 状态是唯一的输出通道：执行结果一律落进 [AgentControl.status] 与
 * [conversation]，发起者与其他客户端从同一处订阅，不会出现
 * 「谁发起谁收」。
 *
 * ## 设计边界：落盘不经本接口
 *
 * 会话内容的落盘由 `ConversationPersister` 继续观察引擎的会话树完成，
 * Room 的 schema 与存量数据不动。因此本接口不含落盘端口，
 * [conversation] 也不承载提交边界（见 `Conversation` 的边界说明）。
 *
 * [load] 需要读取 Room 的那一步住在 app 侧适配器（`ConversationRepo`），
 * 因为 Room 只存在于 app。实现侧将来独立成模块时再引存储端口：那时才有真实
 * 调用点决定端口形状，现在定形状属于为未出现的需求预留。
 */
interface Agent : AgentControl {

    /**
     * 当前会话的内容：会话身份 + 回合列表（含正在流式产生的那一轮）。
     *
     * 读者：
     * - Compose 对话页：整条渲染；
     * - 宿主渲染桥：`AgentRuntimeService.executeTurn` 将来从 `turns` 派生
     *   `RenderFrame`，替代今天对 `LlmStreamEvent` 的折叠。
     *
     * 落盘不读本字段，理由见接口注释的边界说明。
     */
    val conversation: StateFlow<Conversation>

    /**
     * 用户输入草稿：一段文本 + N 张图。多客户端共享同一份。
     *
     * 读者：Compose 输入框；宿主提交路径（先 [updateDraft] 写入提问，
     * 再 [stream]。今天宿主的 `submit` 走独立的 query 参数 +
     * `activeTurn` 的 CAS 锁，接入后改为写草稿 + `stream()`）。
     *
     * FIXME: 遗留 Bug： 部分输入法在 MVI 架构 + Compose 的场景下打字会错乱：打出‘你你好，我我是我是广东我是广东人’这样的增量内容。解决方案不明（如果修不了就不管）
     */
    val draft: StateFlow<Draft>

    /**
     * 变换式更新草稿。
     *
     * 用变换函数而不是 setter，是为了多客户端：两个客户端同时改草稿时，
     * setter 会出现「读到旧值 → 整体覆盖 → 丢掉对方的修改」，
     * 变换函数在归约时拿到的是最新值。消费方只描述「怎么改」，
     * 不需要知道当前值。
     *
     * 本方法是同步的纯变换，因此**不承担 I/O**。图片落盘通过草稿里的
     * `DraftImage.Pending` 表达：添加一个待处理项是纯变换，
     * 实现侧观察到它再完成落盘并归约成 `DraftImage.Ready`。
     * 今天 Compose 的输入与选图直接改自有 UI 状态，接入后收敛到这一处。
     */
    fun updateDraft(transform: (Draft) -> Draft)

    /** 清空草稿。消费方：发送后、新建会话。 */
    fun clearDraft()

    /**
     * 用当前草稿发起一轮。实现在同一次归约里消费掉草稿（发起即清空），
     * 宿主写入与 Compose 输入的覆盖窗口只有一帧。
     *
     * 调用面：
     * - Compose 的 Send 意图（今天在 `HomeChatState.kt` 的发送前自查
     *   空输入与生成中，接入后实现侧统一裁决）；
     * - 宿主 `submit`（今天在 Binder 层拒绝空串与超 8192 字符、
     *   CAS 拒绝忙，接入后改为写草稿 + 本命令，返回值的 `Busy`
     *   替代今天发回宿主的那条「已有回合进行中」错误帧）。
     *
     * @return 命令是否被接受。[TurnStart.Busy] 时实现不在状态里留痕；
     *   失败卡只承载引擎层错误。
     */
    fun stream(): TurnStart

    /**
     * 载入一条已持久化的会话。
     *
     * 调用面：Compose 的历史列表与进入会话（今天走
     * `runtime.openSession(record.snapshot)` 两处）。
     *
     * 载入后 [draft] 是该会话的草稿（今天 `HomeChatState` 恢复时把
     * `record.draftText` 回填输入框），[conversation] 是它的回合列表。
     * 读取持久化内容这一步由 app 侧适配器完成
     * （`ConversationRepo.getConversation` → `open(restore)`）。
     *
     * 实现内部先停止当前回合再重建（今天的「调用方必须先 stop」
     * 义务取消：那是只要忘记就会失败的前提，不应由每个调用方维护）。
     * 实现做的事：停止并关闭当前引擎实例，用该会话的持久化快照
     * 重建实例（现有语义：`close + open(restore)`，
     * 树 id 等于 Room 会话 id）。
     */
    suspend fun load(id: ConversationId)

    /**
     * 丢弃当前会话实例。
     *
     * 调用面：
     * - Compose 新建会话（今天 `HomeChatState` 两处 `resetConversation()`）；
     * - 宿主 reset（今天 `AgentRuntimeService.resetConversation` 经 Binder）；
     * - 宿主 UI 的重置信号（今天
     *   `AbstractAssistantHook.onSessionReset()` → Binder → 服务）。
     *
     * 实现的现有语义（容易被误读，故写明）：杀掉工具资源
     * （Python 进程、终端会话）→ 关闭引擎实例 → 清空对外状态流。
     * **不新建实例**，下一次 [stream] 惰性建一个；
     * **不动持久化记录**，删除历史会话是仓储的事。
     */
    fun discard()
}

/**
 * [Agent.stream] 的返回值：命令是否被接受。
 *
 * 同步可判定的只有这三种：草稿为空、已有活跃回合。
 * 配置缺失、鉴权失败这类必须等实现侧装配（`refresh()`），
 * 仍然是异步落状态，不进返回值。
 */
enum class TurnStart {
    /** 已受理。 */
    Started,

    /** 草稿为空（无文本无图），命令被忽略。 */
    DraftEmpty,

    /** 已有活跃回合，命令被忽略。替代今天的 `TurnConflict` 错误卡。 */
    Busy,
}
