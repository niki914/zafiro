package com.niki914.zafiro.accessibility.tree

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.SemanticType
import com.niki914.zafiro.api.util.tryOrNull
import java.util.Collections
import java.util.IdentityHashMap

/**
 * 屏幕节点解析结果载体。
 */
data class ExtractedScreenTree(
    val root: NodeInfo,
    val flatNodes: List<NodeInfo>,
    val rawNodeMap: Map<Int, AccessibilityNodeInfo>,
    val yaml: String,
)

/**
 * 将 Android 系统原生 [AccessibilityNodeInfo] 树清洗、剪枝并转换为纯 JVM 结构的提取器。
 */
object DisplayNodeExtractor {

    private const val MAX_NODES = 200
    private const val MAX_RAW_VISITED = 1000
    private const val MAX_RAW_DEPTH = 70
    private const val MAX_SEMANTIC_DEPTH = 20

    /**
     * 阶段一产物：已完成结构清洗与剪枝、尚未分配最终编号的纯语义节点。
     */
    private class PrunedNode(
        val rawNode: AccessibilityNodeInfo,
        val semanticType: SemanticType,
        val text: String,
        val contentDesc: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isLongClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isChecked: Boolean,
        val children: List<PrunedNode>,
        val moreSummary: List<String>,
    )

    /**
     * 原生节点资源池：统一托管遍历期间获取的所有 Binder 引用，将 recycle() 彻底从业务剪枝逻辑中剥离。
     */
    private class NodeResourcePool {
        private val acquired = mutableListOf<AccessibilityNodeInfo>()

        val isExhausted: Boolean
            get() = acquired.size >= MAX_RAW_VISITED

        fun track(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
            acquired.add(node)
            return node
        }

        fun releaseAllExcept(retained: Collection<AccessibilityNodeInfo>) {
            val retainedSet = Collections.newSetFromMap(
                IdentityHashMap<AccessibilityNodeInfo, Boolean>(retained.size)
            ).apply {
                addAll(retained)
            }

            for (node in acquired) {
                if (node !in retainedSet) {
                    tryOrNull {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    }
                }
            }
        }
    }

    fun extract(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        version: String,
    ): ExtractedScreenTree {
        val pool = NodeResourcePool()
        var depthExceeded = false

        // Stage 1: 纯结构与语义剪枝（自底向上，不操心编号、配额回滚与手动 recycle）
        val prunedRoots = pruneTree(
            node = pool.track(root),
            pool = pool,
            screenHeight = screenHeight,
            parentType = null,
            rawDepth = 0,
            semanticDepth = 0,
            onDepthExceeded = { depthExceeded = true },
        )

        // Stage 2: 定额截断与连续前序编号（自顶向下，只对最终成活节点分配 0..N 连续索引）
        val rawNodeMap = HashMap<Int, AccessibilityNodeInfo>()
        val flatNodes = mutableListOf<NodeInfo>()
        var indexCounter = 0

        fun materialize(node: PrunedNode): NodeInfo? {
            if (indexCounter >= MAX_NODES) return null
            val idx = indexCounter++
            rawNodeMap[idx] = node.rawNode

            val materializedChildren = node.children.mapNotNull { child ->
                materialize(child)
            }

            val info = NodeInfo(
                index = idx,
                semanticType = node.semanticType,
                text = node.text,
                contentDesc = node.contentDesc,
                bounds = node.bounds,
                isClickable = node.isClickable,
                isLongClickable = node.isLongClickable,
                isEditable = node.isEditable,
                isScrollable = node.isScrollable,
                isChecked = node.isChecked,
                children = materializedChildren,
                moreSummary = node.moreSummary,
            )
            flatNodes.add(info.copy(children = emptyList()))
            return info
        }

        val finalizedChildren = prunedRoots.mapNotNull { materialize(it) }

        // Stage 3: 统一垃圾回收（所有未进入最终 rawNodeMap 的节点在此一键释放，零泄漏、零野指针）
        pool.releaseAllExcept(rawNodeMap.values)

        val rootNode = NodeInfo(
            index = -1,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, screenWidth, screenHeight),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = finalizedChildren,
            moreSummary = emptyList(),
        )

        val yaml = TreeFormatter.toYaml(
            root = rootNode,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            appPackage = appPackage,
            version = version,
            totalNodesCount = flatNodes.size,
            depthExceeded = depthExceeded,
        )

        return ExtractedScreenTree(
            root = rootNode,
            flatNodes = flatNodes.sortedBy { it.index },
            rawNodeMap = rawNodeMap,
            yaml = yaml,
        )
    }

    private fun pruneTree(
        node: AccessibilityNodeInfo,
        pool: NodeResourcePool,
        screenHeight: Int,
        parentType: SemanticType?,
        rawDepth: Int,
        semanticDepth: Int,
        onDepthExceeded: () -> Unit,
    ): List<PrunedNode> {
        if (pool.isExhausted) {
            return emptyList()
        }
        if (rawDepth > MAX_RAW_DEPTH || semanticDepth > MAX_SEMANTIC_DEPTH) {
            onDepthExceeded()
            return emptyList()
        }

        val className = node.className?.toString().orEmpty()
        val text = node.text?.toString().orEmpty()
        val contentDesc = node.contentDescription?.toString().orEmpty()
        val isClickable = node.isClickable
        val isLongClickable = node.isLongClickable
        val isEditable = node.isEditable
        val isScrollable = node.isScrollable
        val isChecked = node.isChecked

        val androidRect = AndroidRect()
        node.getBoundsInScreen(androidRect)
        val bounds = Rect(androidRect.left, androidRect.top, androidRect.right, androidRect.bottom)

        val nodeType = PruningRules.mapSemanticType(className, parentType)

        val isCollapsible = (rawDepth > 0) &&
                nodeType == SemanticType.CONTAINER &&
                text.isEmpty() &&
                contentDesc.isEmpty() &&
                !isClickable &&
                !isLongClickable &&
                !isEditable &&
                !isScrollable &&
                !isChecked

        val nextSemanticDepth = if (isCollapsible) semanticDepth else semanticDepth + 1
        val effectiveParentType = if (isCollapsible) parentType else nodeType

        val childResults = mutableListOf<PrunedNode>()
        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (pool.isExhausted) break
            val rawChild = node.getChild(i) ?: continue
            childResults.addAll(
                pruneTree(
                    node = pool.track(rawChild),
                    pool = pool,
                    screenHeight = screenHeight,
                    parentType = effectiveParentType,
                    rawDepth = rawDepth + 1,
                    semanticDepth = nextSemanticDepth,
                    onDepthExceeded = onDepthExceeded,
                )
            )
        }

        // 1. 折叠穿透无意义包装容器
        if (isCollapsible) {
            return childResults
        }

        // 2. 滚动容器剔除离屏子树并浓缩为摘要
        val moreSummaries = mutableListOf<String>()
        val visibleChildren = if (isScrollable) {
            childResults.filterNot { child ->
                val offScreen = PruningRules.isCompletelyOffScreen(child.bounds, screenHeight)
                if (offScreen) {
                    moreSummaries.add(extractSummaryText(child))
                }
                offScreen
            }
        } else {
            childResults
        }

        // 3. 剪除空壳节点与零面积节点
        val isShell = text.isEmpty() &&
                contentDesc.isEmpty() &&
                !isClickable &&
                !isLongClickable &&
                !isEditable &&
                !isScrollable &&
                !isChecked &&
                visibleChildren.isEmpty()

        if (rawDepth > 0 && (isShell || PruningRules.isZeroArea(bounds))) {
            return visibleChildren
        }

        return listOf(
            PrunedNode(
                rawNode = node,
                semanticType = nodeType,
                text = text,
                contentDesc = contentDesc,
                bounds = bounds,
                isClickable = isClickable,
                isLongClickable = isLongClickable,
                isEditable = isEditable,
                isScrollable = isScrollable,
                isChecked = isChecked,
                children = visibleChildren,
                moreSummary = moreSummaries,
            )
        )
    }

    /**
     * 递归提取离屏节点或其子树中的首个有效文本作为摘要，避免复合卡片容器只输出 "(empty)"。
     */
    private fun extractSummaryText(node: PrunedNode): String {
        fun findFirstLabel(curr: PrunedNode): String? {
            if (curr.text.isNotBlank()) return curr.text.trim()
            if (curr.contentDesc.isNotBlank()) return curr.contentDesc.trim()
            for (child in curr.children) {
                val found = findFirstLabel(child)
                if (found != null) return found
            }
            return null
        }

        val rawSummary = findFirstLabel(node) ?: "(empty)"
        return if (rawSummary.length > 20) rawSummary.take(20) + "…" else rawSummary
    }
}