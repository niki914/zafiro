package com.niki914.zafiro.app.ui.model.conversation

/**
 * 会话在历史列表中的内存运行态。
 *
 * 运行时状态纯内存计算（不入库），由 ViewModel 聚合 Room 实体、AgentManager 活跃状态与未读标记派生。
 */
enum class ConversationRunState {
    /** 空闲态，无特殊指示器。 */
    Idle,

    /** 正在运行中（思考、正文生成或工具执行中），列表展示微型旋转加载圈。 */
    Running,

    /** 等待用户审批高危工具执行，列表展示高亮小铃铛。 */
    WaitingApproval,

    /** 后台静默执行已完成，用户尚未切入查看，列表展示高亮小铃铛。 */
    UnreadCompleted,
}
