package com.niki914.zafiro.chat

import com.niki914.logging.Logger
import com.niki914.okia.Okia
import com.niki914.okia.mcp.McpDiscoveryState
import com.niki914.okia.mcp.McpServerDiscoverySnapshot
import com.niki914.zafiro.api.model.FileRef
import com.niki914.zafiro.api.text.FilesBlock
import com.niki914.zafiro.api.text.TurnTextComposer

/**
 * 回合前缀注入器。
 *
 * 负责构建文件块、异步任务完成通知块、以及 MCP 发现失败说明前缀。
 */
internal class TurnPrefixComposer {
    private companion object {
        const val LOG_TAG = "niki914_zafiro_TurnPrefixComposer"
    }

    @Volatile
    private var mcpNoticeSignature: String? = null

    fun reset() {
        mcpNoticeSignature = null
    }

    /**
     * MCP 状态注入（#switch-refresh 配套）：send 前读发现快照，
     * 对 Loading (Discovering/Idle) 与 Failed 服务器注入快照说明；按签名去重。
     */
    suspend fun mcpNotice(session: Okia?): String? {
        val okia = session ?: return null
        val servers = okia.getMcpDiscoverySnapshot().servers.values.filter { it.enabled }
        Logger.i(
            LOG_TAG,
            "mcp discovery " + servers.sortedBy { it.serverName }
                .joinToString(" ") { "${it.serverName}=${it.state}" },
        )
        val loading = servers.filter {
            it.state == McpDiscoveryState.Discovering || it.state == McpDiscoveryState.Idle
        }.sortedBy { it.serverName }
        val failed = servers.filter {
            it.state == McpDiscoveryState.Failed
        }.sortedBy { it.serverName }
        if (loading.isEmpty() && failed.isEmpty()) {
            mcpNoticeSignature = null
            return null
        }
        val signature = "L:" + loading.joinToString(",") { it.serverName } +
                "|F:" + failed.joinToString(",") { "${it.serverName}:${it.errorMessage.orEmpty()}" }
        if (signature == mcpNoticeSignature) return null
        mcpNoticeSignature = signature
        return buildMcpNotice(loading = loading, failed = failed)
    }

    /** 拼注入前缀：文件块 + 瞬态通知块，排成「块\n\n块」。 */
    fun buildInjectionPrefixes(
        files: List<FileRef>,
        mcpNotice: String?,
        notifications: List<String>,
    ): String? {
        val noticeBody = (listOfNotNull(mcpNotice) + notifications)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val notificationsBlock = noticeBody.takeIf { it.isNotEmpty() }
            ?.let { TurnTextComposer.wrapBlock("notifications", it) }
        val prefixes = listOfNotNull(FilesBlock.block(files), notificationsBlock)
        return prefixes.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    /** MCP 状态快照文案；格式对齐瞬态通知的元信息风格。 */
    fun buildMcpNotice(
        loading: List<McpServerDiscoverySnapshot> = emptyList(),
        failed: List<McpServerDiscoverySnapshot> = emptyList(),
    ): String {
        if (loading.isEmpty() && failed.isEmpty()) return ""
        return buildString {
            append("[Note: MCP status snapshot when this message was sent")
            if (loading.isNotEmpty()) {
                append(". Some tools may not be ready at this moment")
            }
            appendLine(":")
            if (loading.isNotEmpty()) {
                appendLine("- Loading: " + loading.joinToString(", ") { it.serverName })
            }
            failed.forEach { server ->
                val reason =
                    server.errorMessage?.lineSequence()?.firstOrNull()?.trim()?.take(120)?.ifEmpty { null }
                        ?: "unknown error"
                appendLine("- Failed: ${server.serverName} ($reason)")
            }
        }.trimEnd() + "]"
    }
}
