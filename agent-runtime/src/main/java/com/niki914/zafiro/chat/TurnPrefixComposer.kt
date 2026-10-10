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
    private var mcpFailureSignature: String? = null

    fun reset() {
        mcpFailureSignature = null
    }

    /**
     * MCP 发现失败注入（#switch-refresh 配套）：send 前读发现快照，
     * 仅 Failed 服务器注入说明；按「失败集合 + 错误摘要」签名去重。
     */
    suspend fun mcpFailureNotice(session: Okia?): String? {
        val okia = session ?: return null
        val servers = okia.getMcpDiscoverySnapshot().servers.values
        Logger.i(
            LOG_TAG,
            "mcp discovery " + servers.sortedBy { it.serverName }
                .joinToString(" ") { "${it.serverName}=${it.state}" },
        )
        val failed =
            servers.filter { it.state == McpDiscoveryState.Failed }.sortedBy { it.serverName }
        if (failed.isEmpty()) {
            mcpFailureSignature = null
            return null
        }
        val signature = failed.joinToString("|") { "${it.serverName}:${it.errorMessage.orEmpty()}" }
        if (signature == mcpFailureSignature) return null
        mcpFailureSignature = signature
        return buildMcpFailureNotice(failed)
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

    /** 失败注入文案；格式对齐终端完成通知的元信息风格。 */
    fun buildMcpFailureNotice(
        failed: List<McpServerDiscoverySnapshot>,
    ): String = buildString {
        appendLine("[IMPORTANT: MCP discovery failed for the following servers; their tools are unavailable in this turn:")
        failed.forEach { server ->
            val reason =
                server.errorMessage?.lineSequence()?.firstOrNull()?.take(120) ?: "unknown error"
            appendLine("- ${server.serverName}: $reason")
        }
        append(
            "Do not attempt to call their tools. If the task depends on them, " +
                    "tell the user the MCP service is currently unavailable.]",
        )
    }
}
