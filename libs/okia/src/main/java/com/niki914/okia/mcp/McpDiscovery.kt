package com.niki914.okia.mcp

import com.niki914.okia.ImageSaver
import com.niki914.okia.tooling.ToolDescriptor
import com.niki914.okia.tooling.ToolKind
import com.niki914.okia.tooling.ToolRegistry
import com.niki914.okia.tooling.ToolWireName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * MCP 发现管理（internal）：refreshMcpTools 的执行者 + 发现快照的来源。
 *
 * 每服务器状态机：Idle（初始）→ Discovering（刷新中）→ Available（成功）/
 * Failed（失败且无缓存）/ UsingStaleCache（失败但有上次成功缓存）。
 * 刷新并发（对齐 codex join_all）：每台服务器独立 async，网络等待并行；
 * 状态合并与注册清理在 awaitAll 之后按序执行（单线程，无锁竞态）。
 *
 * 注册语义（全量幂等，Q5 裁决）：每次刷新成功 = 该服务器工具集整体替换——
 * 同名覆盖注册、消失的工具从 registry 移除。不做 fingerprint 驱动的差异
 * 更新（注册表量小，覆盖幂等，diff 是过早优化）。fingerprint 只作快照报告
 * 项给 host 读（工具集排序哈希）。
 *
 * 冲突（Q2 裁决）：只检测 DuplicateInServer（同服务器 tools/list 返回同名
 * 多个：保留第一个注册，其余报告进 conflicts）。其余 reason（HiddenByLocal /
 * ExplicitOverridesDiscovered / CrossServerConflict）线缆名经 ToolWireName
 * 按服务器命名空间（mcp__<server>__<tool>）唯一化后无触发路径，枚举保留、
 * 不产生（触发依赖未来特性：无前缀模式 / explicit 配置）。conflicts 只报告，
 * 不参与注册决策。
 *
 * enabled=false 服务器（Q4 修订，2026-08 评审）：刷新跳过、不连接；其已注册工具
 * 在下次刷新时注销（disabled = 不连接、不暴露、不可调用）；discovery snapshot /
 * fingerprint / 诊断信息保留（状态保持），重新 enabled 后经 refresh 重新注册。
 * 从配置删除的服务器同样在下次刷新时注销旧注册。lastSuccessAtMillis 仅记录，
 * 不参与新鲜度判定（Q6 裁决：时间不能证明缓存新鲜）。
 * Design source: kai ToolRegistryResolver（冲突枚举源）；codex tools.rs
 * （重复工具跳过先例）、connection_manager（并发发现 join_all）。
 */
@OptIn(ExperimentalTime::class)
internal class McpDiscovery(
    private val client: McpClient,
    private val servers: () -> List<McpServer>,
    private val registry: () -> ToolRegistry,
    private val imageSaver: ImageSaver? = null
) {

    // 每服务器执行器（注册给发现的工具；servers 解析闭包读最新配置）
    private val executor = McpExecutor(client, { name ->
        servers().firstOrNull { it.name == name }
    }, imageSaver)

    // 当前发现快照（不可变整体替换；@Volatile 免锁读取，KMP 无 concurrent map）
    @Volatile
    private var snapshot: McpDiscoverySnapshot = McpDiscoverySnapshot(emptyMap(), emptyList())

    // 每服务器上次成功注册的工具线缆名（清理消失工具的依据；全量替换语义下
    // 键=server 名，值为已注册 wireName 集合）。
    private val registeredNames = HashMap<String, Set<String>>()

    // 串行化刷新流程（两个并发 refresh 只有一个执行；门面活跃回合检查之外的口）
    private val mutex = Mutex()

    /** 当前发现快照：config 里每台服务器至少一个条目（未刷新 = Idle 初始态）。
     *  enabled 标志以当前配置为准（服务器被禁用 / 重新启用后同步更新，评审
     *  发现 7 状态转换语义）。 */
    fun current(): McpDiscoverySnapshot {
        val configured = servers()
        if (configured.isEmpty()) return snapshot
        val byName = configured.associate { server ->
            val snap = snapshot.servers[server.name] ?: initialState(server)
            server.name to snap.copy(enabled = server.enabled)
        }
        return snapshot.copy(servers = byName)
    }

    /** 刷新全部 enabled 服务器（并发）；结果按服务器分类供 host 暴露连接问题。 */
    suspend fun refresh(): McpRefreshResult {
        return mutex.withLock {
            // 清理不生效服务器的旧注册（评审发现 7，Q4 修订）：removed（不在配置）
            // 与 disabled（enabled=false）服务器都从 registry 注销——disabled 语义 =
            // 不连接、不暴露、不可调用；discovery snapshot / fingerprint / 诊断信息
            // 保留（状态保持），重新 enabled 后经 refresh 重新注册。
            val activeNames = servers().filter { it.enabled }.map { it.name }.toSet()
            registeredNames.keys.toList().forEach { name ->
                if (name !in activeNames) {
                    registeredNames.remove(name)?.forEach { registry().remove(it) }
                }
            }

            val enabledServers = servers().filter { it.enabled }
            val working = current().servers.toMutableMap()

            // 全部 enabled 先置 Discovering（保留旧 tools / errorMessage，host 可观察进行中）
            for (server in enabledServers) {
                working[server.name] = (working[server.name] ?: initialState(server))
                    .copy(state = McpDiscoveryState.Discovering)
            }
            snapshot = snapshot.copy(servers = working.toMap(), conflicts = emptyList())

            if (enabledServers.isEmpty()) {
                return@withLock McpRefreshResult(emptyList(), emptyList())
            }

            // 网络阶段并发（awaitAll 后单线程做状态合并，无锁竞态）
            val outcomes = coroutineScope {
                enabledServers.map { server ->
                    async {
                        try {
                            RefreshOutcome.Ok(server, client.discoverTools(server))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            RefreshOutcome.Err(server, e)
                        }
                    }
                }.awaitAll()
            }

            val refreshed = mutableListOf<String>()
            val failed = mutableListOf<String>()
            val conflicts = mutableListOf<ToolConflict>()
            for (outcome in outcomes) {
                when (outcome) {
                    is RefreshOutcome.Ok -> {
                        val (deduped, conflict) = dedupe(outcome.server.name, outcome.tools)
                        registerAll(outcome.server, deduped)
                        working[outcome.server.name] = successState(outcome.server, deduped)
                        conflict?.let { conflicts += it }
                        refreshed += outcome.server.name
                    }

                    is RefreshOutcome.Err -> {
                        val old = working[outcome.server.name]
                        val hasCache = registeredNames[outcome.server.name]?.isNotEmpty() == true
                        working[outcome.server.name] = McpServerDiscoverySnapshot(
                            serverName = outcome.server.name,
                            enabled = old?.enabled ?: true,
                            fingerprint = old?.fingerprint,
                            state = if (hasCache) McpDiscoveryState.UsingStaleCache else McpDiscoveryState.Failed,
                            errorMessage = outcome.error.message ?: outcome.error::class.simpleName,
                            lastSuccessAtMillis = old?.lastSuccessAtMillis,
                            discoveredToolCount = old?.discoveredToolCount ?: 0,
                            tools = old?.tools ?: emptyList()
                        )
                        failed += outcome.server.name
                    }
                }
            }
            snapshot = snapshot.copy(servers = working.toMap(), conflicts = conflicts)
            McpRefreshResult(refreshed, failed)
        }
    }

    // ── 注册与状态 ─────────────────────────────────────────────────────────

    // 全量幂等注册：先移除该服务器旧注册（消除哈希消歧改名时的残留旧键），
    // 再按确定性顺序（原始名排序）重新派生线缆名并注册。线缆名经
    // ToolWireName 规范化 + 哈希消歧，保证 provider 约束安全且当前 registry
    // 内唯一；registeredNames 同步为本次注册的 wireName 集。
    private fun registerAll(server: McpServer, tools: List<McpDiscoveredTool>) {
        registeredNames[server.name]?.forEach { registry().remove(it) }
        val used = registry().snapshot().map { it.descriptor.wireName }.toMutableSet()
        val sorted = tools.sortedBy { it.name }
        val newNames = LinkedHashSet<String>()
        for (tool in sorted) {
            val rawIdentity = "${server.name}\u0000${tool.name}"
            val wireName = ToolWireName.disambiguate(
                base = ToolWireName.forMcp(server.name, tool.name),
                rawIdentity = rawIdentity,
                used = used
            )
            used.add(wireName)
            registry().register(
                ToolDescriptor(
                    name = tool.name,
                    description = tool.description ?: "",
                    inputSchemaJson = tool.inputSchemaJson,
                    kind = ToolKind.Mcp(server.name),
                    wireName = wireName
                ),
                executor
            )
            newNames += wireName
        }
        registeredNames[server.name] = newNames
    }

    private fun successState(server: McpServer, tools: List<McpDiscoveredTool>) =
        McpServerDiscoverySnapshot(
            serverName = server.name,
            enabled = true,
            fingerprint = fingerprintOf(tools),
            state = McpDiscoveryState.Available,
            errorMessage = null,
            lastSuccessAtMillis = Clock.System.now().toEpochMilliseconds(),
            discoveredToolCount = tools.size,
            tools = tools
        )

    private fun initialState(server: McpServer) = McpServerDiscoverySnapshot(
        serverName = server.name,
        enabled = server.enabled,
        fingerprint = null,
        state = McpDiscoveryState.Idle,
        errorMessage = null,
        lastSuccessAtMillis = null,
        discoveredToolCount = 0,
        tools = emptyList()
    )

    // ── 冲突（Q2：仅 DuplicateInServer） ───────────────────────────────────

    // 服务器内同名：保留第一个，冲突报告（candidates 为参与冲突的注册线缆名，去重）。
    // 与 codex 差异：codex 也跳过重复工具（warn），但碰撞名用 hash 后缀消歧；
    // 本库注册名已带服务器前缀唯一，重复只可能是服务器自身的 bug，无需后缀。
    private fun dedupe(
        serverName: String,
        tools: List<McpDiscoveredTool>
    ): Pair<List<McpDiscoveredTool>, ToolConflict?> {
        val seenNames = HashSet<String>()
        val kept = ArrayList<McpDiscoveredTool>(tools.size)
        var duplicatedRegisteredName: String? = null
        for (tool in tools) {
            if (!seenNames.add(tool.name)) {
                duplicatedRegisteredName = ToolWireName.forMcp(serverName, tool.name)
            } else {
                kept += tool
            }
        }
        val conflict = duplicatedRegisteredName?.let { name ->
            ToolConflict(
                name = name,
                reason = ToolConflictReason.DuplicateInServer,
                candidates = listOf(name)
            )
        }
        return kept to conflict
    }

    // ── fingerprint（Q5：仅报告，驱动不了库内差异更新） ────────────────────

    // 工具集（name/description/schema）排序拼接的多项式哈希；碰撞无害
    // （报告用途，不驱动行为）。
    private fun fingerprintOf(tools: List<McpDiscoveredTool>): String {
        if (tools.isEmpty()) return ""
        val canonical = tools
            .map { "${it.name}\u0000${it.description.orEmpty()}\u0000${it.inputSchemaJson.orEmpty()}" }
            .sorted()
            .joinToString("\u0001")
        var h = 0
        for (c in canonical) h = h * 31 + c.code
        return h.toString(16)
    }
}

/** 单台服务器刷新结果（网络阶段并发返回，合并阶段按序处理）。 */
private sealed interface RefreshOutcome {
    data class Ok(val server: McpServer, val tools: List<McpDiscoveredTool>) : RefreshOutcome
    data class Err(val server: McpServer, val error: Exception) : RefreshOutcome
}