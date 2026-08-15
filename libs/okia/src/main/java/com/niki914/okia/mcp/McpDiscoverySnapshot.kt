package com.niki914.okia.mcp

/**
 * 每服务器的当前发现状态。host 读取它以组合 prompt、暴露连接问题或
 * 持久化发现的工具；库在配置变更时刷新。fingerprint 驱动增量刷新决策。
 * Design source: okia 骨架 McpDiscoverySnapshot（Nexus 重度使用：
 * fingerprint 刷新 + PromptComposer 渲染）。
 */
data class McpDiscoverySnapshot(
    val servers: Map<String, McpServerDiscoverySnapshot>,
    val conflicts: List<ToolConflict>
)

/** 每服务器发现状态。tools 为发现的工具详情（host 组合 prompt 或持久化用）。 */
data class McpServerDiscoverySnapshot(
    val serverName: String,
    val enabled: Boolean,
    val fingerprint: String?,
    val state: McpDiscoveryState,
    val errorMessage: String?,
    val lastSuccessAtMillis: Long?,
    val discoveredToolCount: Int,
    val tools: List<McpDiscoveredTool> = emptyList()
)

/**
 * 发现生命周期状态。过期判定以本枚举为唯一权威：UsingStaleCache = 旧缓存
 * 可用但过期，host 据此判断是否需要刷新（无独立 stale 布尔字段）。
 */
enum class McpDiscoveryState {
    Idle,
    Discovering,
    Available,
    Failed,
    UsingStaleCache
}

/** 被多个发现源认领的工具名。 */
data class ToolConflict(
    val name: String,
    val reason: ToolConflictReason,
    val candidates: List<String>
)

/** 工具名跨发现源冲突的原因。 */
enum class ToolConflictReason {
    HiddenByLocal,
    ExplicitOverridesDiscovered,
    DuplicateInServer,
    CrossServerConflict
}

/**
 * 一次显式刷新的结果。host 用 failedServers 暴露连接问题，
 * 不解析异常。
 * Design source: okia 骨架 McpRefreshResult。
 */
data class McpRefreshResult(
    val refreshedServers: List<String>,
    val failedServers: List<String>
)
