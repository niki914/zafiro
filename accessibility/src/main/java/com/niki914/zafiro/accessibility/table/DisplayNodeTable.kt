package com.niki914.zafiro.accessibility.table

import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.NodeTable
import com.niki914.zafiro.api.screen.SemanticToken
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * 屏幕专属的节点与版本映射表。
 *
 * 核心原则：
 * 1. 实例隔离：每个 [com.niki914.zafiro.api.screen.Screen] 实例持有一份独立的 [DisplayNodeTable]；
 * 2. 独立版本：每次抓取快照更新属于该屏的版本号，主屏与虚拟副屏版本互不干扰；
 * 3. 过期拦截：[resolve] 检查 [SemanticToken.version]，版本不匹配精准识别为过期（Stale）。
 */
class DisplayNodeTable : NodeTable {
    private val rng = SecureRandom()
    private val nodeCache = ConcurrentHashMap<Int, NodeInfo>()

    @Volatile
    override var version: String = ""
        private set

    fun mintVersion(): String {
        val bytes = ByteArray(6)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun remember(version: String, nodes: List<NodeInfo>): String {
        nodeCache.clear()
        nodes.forEach { node ->
            nodeCache[node.index] = node
        }
        this.version = version
        return version
    }

    override fun remember(nodes: List<NodeInfo>): String {
        return remember(mintVersion(), nodes)
    }

    override fun resolve(token: SemanticToken): NodeInfo? {
        if (token.version != version) return null
        return nodeCache[token.index]
    }

    fun clear() {
        nodeCache.clear()
        version = ""
    }

    /** 当前缓存中的节点数量。 */
    val nodeCount: Int get() = nodeCache.size

    /** 导出当前所有缓存的节点（按索引排序）。 */
    fun allNodes(): List<NodeInfo> = nodeCache.values.sortedBy { it.index }
}
