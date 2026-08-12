package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.protocol.ChatProtocol
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 库门面：一次对话一个实例，至多一个活跃回合。send 启动回合；stop 取消回合。
 * 并发调用（活跃回合存在时再次 send）抛异常；Replace 由 stop() + send() 组合表达。
 * Design source: independent facade design, surface from pi session-manager.
 */
interface Okia {

    // 对话状态流：UI 观察它渲染全部内容（协议无关，见 PRD §5.4）
    val conversation: StateFlow<Conversation>

    // 一次性事件流：失败等瞬时事件
    val events: SharedFlow<TurnEvent>

    // 提交用户输入，跑完整个回合（LLM ↔ 工具循环）后返回回合结局。
    // 终态（Stop / Length / Error / Aborted / IdleTimeout / RetryExhausted）
    // 由返回值承载，失败不抛异常；onEvent 承担流式中间过程。
    suspend fun send(
        text: String,
        options: TurnOptions? = null,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult = TODO()

    // 取消当前回合；kill-then-stop（先杀工具资源再取消 job）
    suspend fun stop(): Unit = TODO()

    // 新建实例：fork 当前对话路径，节点不可变共享
    suspend fun fork(): Okia = TODO()

    // 原地移动 leafId 到过去的条目，被跳过的尾部保留在树中。
    // 不校验目标合法性（放开）：回退粒度由下游自行约束，非法回退的
    // 后果（如停在未配对工具调用上）由下游负责。
    suspend fun rewind(entryId: String): Unit = TODO()

    // 导出当前会话持久化快照（完整树 + leafId + 身份）。host 经
    // SessionCodec 编码后自行决定存储位置；恢复时重新 open(restore = ...)。
    suspend fun export(): SessionSnapshot = TODO()

    // 热更新配置快照（hooks 列表可调）
    suspend fun update(block: OkiaConfig.Builder.() -> Unit): Unit = TODO()

    // 当前配置快照
    suspend fun config(): OkiaConfig = TODO()

    // 刷新 MCP 工具发现
    suspend fun refreshMcpTools(): McpRefreshResult = TODO()

    // 当前 MCP 发现快照
    suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot = TODO()

    // 释放实例资源
    suspend fun close(): Unit = TODO()

    companion object {

        // 按协议实例绑定；协议作用域 == Okia 实例生命周期（W5）。
        // 实例由调用方构造（withCodec / 自定义状态在 open 前就绪）；
        // KClass/reified 重载已删除：KMP 无反射，类型令牌无法实例化任意协议。
        // restore 为可选恢复快照（export() 的产物，§5.3）：null = 新对话。
        suspend fun <P : ChatProtocol> open(
            protocol: P,
            restore: SessionSnapshot? = null,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = TODO()

        // 默认协议版本（M0 DeepSeek），库内部构造协议实例
        suspend fun open(
            restore: SessionSnapshot? = null,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = TODO()

        // 显式依赖装配（JVM 测试注入点）
        suspend fun open(
            dependencies: OkiaDependencies,
            restore: SessionSnapshot? = null,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = TODO()
    }
}
