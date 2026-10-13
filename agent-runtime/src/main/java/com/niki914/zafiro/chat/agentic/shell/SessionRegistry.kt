package com.niki914.zafiro.chat.agentic.shell

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话登记：别名/句柄 → 会话元数据与在途命令状态。
 *
 * 进程级单例，与 TerminalSessionPool 同域。别名与句柄都按 [scope] 隔离（本地 shell 与 ssh
 * 各一套命名空间）：否则 ssh 工具带的别名或句柄会解析到本地会话上、命令跑错机器（真机复现过）。
 * 这里只记「开过」，生死以池为准（见 BaseSessionBuiltin.isSessionAlive）。
 */
object SessionRegistry {
    /** 本地 shell（user / root / shizuku）的别名域。 */
    const val SCOPE_LOCAL = "local"

    /** SSH 会话的别名域。 */
    const val SCOPE_SSH = "ssh"

    /** 会话元数据键：会话固化下来的执行身份（`user` / `root` / `shizuku` / `ssh`）。 */
    const val META_IDENTITY = "identity"

    private val byId = ConcurrentHashMap<String, SessionMeta>()
    private val byAlias = ConcurrentHashMap<String, String>()

    private fun aliasKey(scope: String, alias: String): String = "$scope\u0000$alias"

    fun register(
        sessionId: String,
        alias: String?,
        scope: String = SCOPE_LOCAL,
        metadata: Map<String, String> = emptyMap(),
    ): SessionMeta {
        if (alias != null) {
            val existingSession = byAlias.putIfAbsent(aliasKey(scope, alias), sessionId)
            if (existingSession != null && existingSession != sessionId) {
                throw IllegalArgumentException(
                    "Session alias '$alias' is already in use by session '$existingSession'."
                )
            }
        }
        val meta = SessionMeta(
            sessionId = sessionId,
            alias = alias,
            scope = scope,
            metadata = metadata,
        )
        byId[sessionId] = meta
        return meta
    }

    fun resolveSessionId(target: String?, scope: String = SCOPE_LOCAL): String? {
        if (target.isNullOrBlank()) return null
        byAlias[aliasKey(scope, target)]?.let { return it }
        // 句柄全局唯一，但必须落在本域：句柄是池级共享的，跨域放行等于让另一个工具执行它
        val meta = byId[target] ?: return null
        return meta.sessionId.takeIf { meta.scope == scope }
    }

    fun findById(sessionId: String): SessionMeta? = byId[sessionId]

    fun findByAlias(alias: String, scope: String = SCOPE_LOCAL): SessionMeta? {
        val id = byAlias[aliasKey(scope, alias)] ?: return null
        return byId[id]
    }

    fun updateCommandState(sessionId: String, state: SessionCommandState?) {
        byId[sessionId]?.activeCommand = state
    }

    fun getCommandState(sessionId: String): SessionCommandState? {
        return byId[sessionId]?.activeCommand
    }

    fun unregister(sessionId: String): SessionMeta? {
        val meta = byId.remove(sessionId)
        if (meta?.alias != null) {
            byAlias.remove(aliasKey(meta.scope, meta.alias), sessionId)
        }
        return meta
    }

    fun all(): List<SessionMeta> = byId.values.toList()

    fun clear() {
        byId.clear()
        byAlias.clear()
    }
}

data class SessionMeta(
    val sessionId: String,
    val alias: String?,
    /** 别名域，取值见 [SessionRegistry.SCOPE_LOCAL] / [SessionRegistry.SCOPE_SSH]。 */
    val scope: String = SessionRegistry.SCOPE_LOCAL,
    val createdAt: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap(),
    @Volatile var activeCommand: SessionCommandState? = null,
)

data class SessionCommandState(
    val command: String,
    val token: String,
    val startTimeMs: Long,
    val initialOffset: Int = 0,
    @Volatile var status: String = "running",
    @Volatile var exitCode: Int? = null,
    @Volatile var cleanOutput: String = "",
    @Volatile var timedOut: Boolean = false,
    /** 命令结束时刻；null 表示仍在运行。 */
    @Volatile var endTimestampMs: Long? = null,
    /** 全量输出是否已经通过某条回执交付过；重复 read 只回 already_read。 */
    @Volatile var outputDelivered: Boolean = false,
    /** action='read' 的差分游标，初值与 initialOffset 对齐。 */
    @Volatile var lastReadOffset: Int = initialOffset,
)
