package com.niki914.okia

import com.niki914.okia.conversation.Conversation
import com.niki914.okia.conversation.SessionSnapshot
import com.niki914.okia.event.TurnEvent
import com.niki914.okia.loop.AgentLoop
import com.niki914.okia.loop.RealAgentLoop
import com.niki914.okia.loop.TurnResult
import com.niki914.okia.mcp.AutoDetectMcpClient
import com.niki914.okia.mcp.DiscoveryStreamableHttpMcpClient
import com.niki914.okia.mcp.LegacyStreamableHttpMcpClient
import com.niki914.okia.mcp.McpClient
import com.niki914.okia.mcp.McpDiscoverySnapshot
import com.niki914.okia.mcp.McpRefreshResult
import com.niki914.okia.mcp.McpServer
import com.niki914.okia.protocol.ChatProtocol
import com.niki914.okia.protocol.OpenAIChatCompletionProtocol
import com.niki914.okia.protocol.ProtocolCompatMapper
import com.niki914.okia.transport.HttpEngine
import com.niki914.okia.transport.OkHttpEngine
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 库门面：一次对话一个实例，至多一个活跃回合。send 启动回合；stop 取消回合。
 * 并发契约：活跃回合存在时，send 与任何改变会话状态的操作（rewind /
 * update / refreshMcpTools / close）都抛异常；stop 是唯一例外（取消路径）。
 * 无 fork：分支由下游 export() + open(restore) 自行实现，库只维护单棵对话树。
 * Replace 由 stop() + send() 组合表达。
 * Design source: independent facade design, surface from pi session-manager.
 */
interface Okia {

    // 对话状态流：UI 观察它渲染全部内容（协议无关，见 PRD §5.4）
    val conversation: StateFlow<Conversation>

    // 一次性事件流：失败等瞬时事件
    val events: SharedFlow<TurnEvent>

    // 提交用户输入，跑完整个回合（LLM ↔ 工具循环）后返回回合结局。
    // 终态由 sealed TurnResult 承载（Completed / Failed / Aborted / IdleTimeout），
    // 失败不抛异常；onEvent 承担流式中间过程。
    suspend fun send(
        text: String,
        options: TurnOptions? = null,
        onEvent: suspend (TurnEvent) -> Unit
    ): TurnResult

    // 取消当前回合；kill-then-stop（先杀工具资源再取消 job）
    suspend fun stop(): Unit

    // 原地移动 leafId 到过去的条目，被跳过的尾部保留在树中。
    // entryId 不存在时抛 IllegalArgumentException；位置语义不校验（放开）：
    // 停在未配对工具调用等位置由下游负责。改第一条消息 = 新建实例（§5.1），
    // 库不提供回退到 root 的 API。
    suspend fun rewind(entryId: String): Unit

    // 导出当前会话持久化快照（完整树 + leafId + 身份）。host 经
    // SessionCodec 编码后自行决定存储位置；恢复时重新 open(restore = ...)。
    suspend fun export(): SessionSnapshot

    // 热更新配置快照（hooks 列表可调）
    suspend fun update(block: OkiaConfig.Builder.() -> Unit): Unit

    // 当前配置快照
    suspend fun config(): OkiaConfig

    // 刷新 MCP 工具发现（MCP 推迟 T9，当前实现抛未实现）
    suspend fun refreshMcpTools(): McpRefreshResult

    // 当前 MCP 发现快照（MCP 推迟 T9，当前实现抛未实现）
    suspend fun getMcpDiscoverySnapshot(): McpDiscoverySnapshot

    // 释放实例资源
    suspend fun close(): Unit

    companion object {

        // 按协议实例绑定；协议作用域 == Okia 实例生命周期（W5）。
        // 实例由调用方构造（withCodec / 自定义状态在 open 前就绪）；
        // KClass/reified 重载已删除：KMP 无反射，类型令牌无法实例化任意协议。
        // restore 为可选恢复快照（export() 的产物，§5.3）：null = 新对话。
        // endpoint 解析（方案 A，§8.17）：builder.endpoint 显式设置时用配置值；
        // 为空时用协议 defaultEndpoint；两者皆空抛 IllegalArgumentException（fail-fast）。
        suspend fun <P : ChatProtocol> open(
            protocol: P,
            restore: SessionSnapshot? = null,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = assemble(protocol, restore, builder, fillDefaults = false)

        // 默认协议版本（M0 DeepSeek 形态：通用 OpenAI Chat 协议 + DeepSeekCompat），
        // 库内部构造协议实例。
        // builder.model 为空时填默认模型（deepseek-v4-flash，用户裁决 2026-08-16）。
        suspend fun open(
            restore: SessionSnapshot? = null,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia = assemble(OpenAIChatCompletionProtocol(), restore, builder, fillDefaults = true)

        // 显式依赖装配（JVM 测试注入点）
        suspend fun open(
            dependencies: OkiaDependencies,
            restore: SessionSnapshot? = null,
            builder: OkiaConfig.Builder.() -> Unit
        ): Okia {
            val config = OkiaConfig.Builder().apply(builder).build()
            return RealOkia(dependencies, restore, config)
        }

        // 装配：默认值与 endpoint 解析 + 依赖构造（agentLoop / mapper / mcpClient 占位）
        private fun <P : ChatProtocol> assemble(
            protocol: P,
            restore: SessionSnapshot?,
            builder: OkiaConfig.Builder.() -> Unit,
            fillDefaults: Boolean
        ): Okia {
            val config = OkiaConfig.Builder().apply(builder).let { b ->
                if (fillDefaults && b.model.isBlank()) b.model = DEFAULT_MODEL
                if (b.endpoint.isBlank()) {
                    b.endpoint = protocol.defaultEndpoint ?: throw IllegalArgumentException(
                        "endpoint is required: protocol '${protocol.id}' declares no defaultEndpoint " +
                            "and builder.endpoint is empty"
                    )
                }
                b.build()
            }
            val dependencies = DefaultDependencies(
                agentLoop = RealAgentLoop(),
                protocolMapper = ProtocolCompatMapper.from(protocol),
                mcpClient = buildDefaultMcpClient(config.httpEngine ?: OkHttpEngine())
            )
            return RealOkia(dependencies, restore, config)
        }

        // 默认装配的模型（M0 DeepSeek）
        private const val DEFAULT_MODEL = "deepseek-v4-flash"
    }
}

/**
 * 默认依赖装配（open(protocol) / open() 内部使用）：agentLoop 为库默认实现、
 * mapper 经协议构造、mcpClient 为 AutoDetect 默认客户端（T9b 落地，Q7：
 * 复用 config.httpEngine，宿主注入时复用宿主资源，未注入用默认 OkHttpEngine）。
 * 测试注入点仍是 open(dependencies)，本类 internal 不对外。
 */
internal class DefaultDependencies(
    override val agentLoop: AgentLoop,
    override val protocolMapper: ProtocolCompatMapper,
    override val mcpClient: McpClient
) : OkiaDependencies

// 默认 MCP 客户端装配：AutoDetect 包装两协议类（线缆共享 engine）。
// engine 为 config.httpEngine 或新建默认（Q7 裁决：复用 host 注入的传输入口）。
private fun buildDefaultMcpClient(engine: HttpEngine): McpClient {
    val legacy = LegacyStreamableHttpMcpClient(engine)
    val discovery = DiscoveryStreamableHttpMcpClient(engine)
    return AutoDetectMcpClient(legacy, discovery)
}