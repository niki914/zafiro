package com.niki914.zafiro.accessibility.tree

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo
import com.niki914.zafiro.api.screen.NodeInfo
import com.niki914.zafiro.api.util.tryOrNull
import com.niki914.zafiro.api.screen.Rect
import com.niki914.zafiro.api.screen.SemanticType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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

    fun extract(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        version: String,
    ): ExtractedScreenTree {
        val indexCounter = AtomicInteger(0)
        val nodeCounter = AtomicInteger(0)
        val depthExceeded = AtomicBoolean(false)
        val rawNodeMap = ConcurrentHashMap<Int, AccessibilityNodeInfo>()
        val flatNodes = mutableListOf<NodeInfo>()

        val childNodes = buildTree(
            node = root,
            screenHeight = screenHeight,
            parentType = null,
            depth = 0,
            indexCounter = indexCounter,
            nodeCounter = nodeCounter,
            depthExceeded = depthExceeded,
            rawNodeMap = rawNodeMap,
            flatNodes = flatNodes,
        )

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
            children = childNodes,
            moreSummary = emptyList(),
        )

        val yaml = TreeFormatter.toYaml(
            root = rootNode,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            appPackage = appPackage,
            version = version,
            totalNodesCount = nodeCounter.get(),
            depthExceeded = depthExceeded.get(),
        )

        return ExtractedScreenTree(
            root = rootNode,
            flatNodes = flatNodes.sortedBy { it.index },
            rawNodeMap = rawNodeMap,
            yaml = yaml,
        )
    }

    private fun buildTree(
        node: AccessibilityNodeInfo,
        screenHeight: Int,
        parentType: SemanticType?,
        depth: Int,
        indexCounter: AtomicInteger,
        nodeCounter: AtomicInteger,
        depthExceeded: AtomicBoolean,
        rawNodeMap: MutableMap<Int, AccessibilityNodeInfo>,
        flatNodes: MutableList<NodeInfo>,
    ): List<NodeInfo> {
        if (nodeCounter.get() >= 200) {
            recycle(node)
            return emptyList()
        }
        if (depth > 20) {
            depthExceeded.set(true)
            recycle(node)
            return emptyList()
        }

        val className = node.className?.toString() ?: ""
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

        nodeCounter.incrementAndGet()
        val nodeIndex = indexCounter.getAndIncrement()

        // 记录原始无障碍节点映射，供动作执行（ACTION_CLICK 等）直接调用
        rawNodeMap[nodeIndex] = node

        val childResults = mutableListOf<NodeInfo>()
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            childResults.addAll(
                buildTree(
                    node = child,
                    screenHeight = screenHeight,
                    parentType = nodeType,
                    depth = depth + 1,
                    indexCounter = indexCounter,
                    nodeCounter = nodeCounter,
                    depthExceeded = depthExceeded,
                    rawNodeMap = rawNodeMap,
                    flatNodes = flatNodes,
                )
            )
        }

        val moreSummaries = mutableListOf<String>()
        if (isScrollable) {
            val filtered = childResults.filterNot { child ->
                val offScreen = PruningRules.isCompletelyOffScreen(child.bounds, screenHeight)
                if (offScreen) moreSummaries.add(PruningRules.buildMoreSummary(child))
                offScreen
            }
            childResults.clear()
            childResults.addAll(filtered)
        }

        val candidate = NodeInfo(
            index = nodeIndex,
            semanticType = nodeType,
            text = text,
            contentDesc = contentDesc,
            bounds = bounds,
            isClickable = isClickable,
            isLongClickable = isLongClickable,
            isEditable = isEditable,
            isScrollable = isScrollable,
            isChecked = isChecked,
            children = childResults.toList(),
            moreSummary = moreSummaries.toList(),
        )

        // 导出的扁平列表清空 children，防止搜索遍历时重复递归
        flatNodes.add(candidate.copy(children = emptyList()))

        if (PruningRules.isEmptyShell(candidate) && depth > 0) {
            return childResults
        }

        if (PruningRules.shouldCollapse(candidate, depth)) {
            return childResults
        }

        if (depth > 0 && PruningRules.isZeroArea(candidate.bounds)) {
            return childResults
        }

        return listOf(candidate)
    }

    private fun recycle(node: AccessibilityNodeInfo) {
        tryOrNull {
            @Suppress("DEPRECATION")
            node.recycle()
        }
    }
}
