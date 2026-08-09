package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.protocol.ChatProtocol
import kotlin.reflect.KClass
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

    // 提交用户输入，跑完整个回合（LLM ↔ 工具循环）后返回
    suspend fun send(
        text: String,
        options: TurnOptions? = null,
        onEvent: suspend (TurnEvent) -> Unit
    ): Unit = TODO()

    // 取消当前回合；kill-then-stop（先杀工具资源再取消 job）
    suspend fun stop(): Unit = TODO()

    // 新建实例：fork 当前对话路径，节点不可变共享
    suspend fun fork(): Okia = TODO()

    // 原地移动 leafId 到过去的条目，被跳过的尾部保留在树中
    suspend fun rewind(entryId: String): Unit = TODO()

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

        // 按协议类绑定实例化；协议作用域 == Okia 实例生命周期
        suspend fun <P : ChatProtocol> open(
            protocolClass: KClass<P>,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = TODO()

        // reified 重载
        suspend inline fun <reified P : ChatProtocol> open(
            noinline builder: OkiaConfig.Builder.() -> Unit
        ): Okia = open(P::class, builder)

        // 默认协议版本（M0 DeepSeek）
        @JvmName("openDefault")
        suspend fun open(
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = TODO()

        // 显式依赖装配（JVM 测试注入点）
        suspend fun open(
            dependencies: OkiaDependencies,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = TODO()
    }
}
