package com.niki914.nexus.agentic.chat.agentic.accessibility

import android.graphics.Rect as AndroidRect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object TreeFormatter {

    fun format(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
    ): String {
        val indexCounter = AtomicInteger(0)
        val nodeCounter = AtomicInteger(0)
        val depthExceeded = AtomicBoolean(false)

        val nodes = buildTree(
            node = root,
            screenHeight = screenHeight,
            parentType = null,
            depth = 0,
            indexCounter = indexCounter,
            nodeCounter = nodeCounter,
            depthExceeded = depthExceeded,
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
            children = nodes,
            moreCount = 0,
        )
        return toYaml(rootNode, screenWidth, screenHeight, appPackage, nodeCounter, depthExceeded)
    }

    private fun buildTree(
        node: AccessibilityNodeInfo,
        screenHeight: Int,
        parentType: SemanticType?,
        depth: Int,
        indexCounter: AtomicInteger,
        nodeCounter: AtomicInteger,
        depthExceeded: AtomicBoolean,
    ): List<NodeInfo> {
        if (nodeCounter.get() >= 200) return emptyList()
        if (depth > 20) {
            depthExceeded.set(true)
            return emptyList()
        }

        val className = node.className?.toString() ?: ""
        val text = PruningRules.normalizeText(node.text?.toString() ?: "")
        val contentDesc = PruningRules.normalizeText(node.contentDescription?.toString() ?: "")
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
                )
            )
        }

        var moreCount = 0
        if (isScrollable) {
            val filtered = childResults.filterNot { child ->
                val offScreen = PruningRules.isCompletelyOffScreen(child.bounds, screenHeight)
                if (offScreen) moreCount++
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
            moreCount = moreCount,
        )

        if (PruningRules.isEmptyShell(candidate) && depth > 0) {
            return childResults
        }

        return listOf(candidate)
    }

    private fun toYaml(
        node: NodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        nodeCounter: AtomicInteger,
        depthExceeded: AtomicBoolean,
    ): String {
        val sb = StringBuilder()
        sb.append("screen: [$screenWidth, $screenHeight]\n")
        sb.append("app: $appPackage\n")
        sb.append("tree:\n")
        for (child in node.children) {
            sb.append("  - ${nodeToYamlLine(child, indent = 2)}\n")
        }
        if (nodeCounter.get() >= 200) {
            sb.append("# truncated: max_nodes(200)\n")
        }
        if (depthExceeded.get()) {
            sb.append("# truncated: max_depth(20)\n")
        }
        return sb.toString()
    }

    private fun nodeToYamlLine(node: NodeInfo, indent: Int): String {
        val sb = StringBuilder()
        sb.append("{i: ${node.index}, t: ${node.semanticType.name.lowercase()}, b: [${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]")

        if (node.text.isNotEmpty()) {
            sb.append(", txt: ${quoteIfNeeded(node.text)}")
        }

        if (node.contentDesc.isNotEmpty()) {
            sb.append(", h: ${quoteIfNeeded(node.contentDesc)}")
        }

        if (node.isClickable) sb.append(", tap: true")
        if (node.isLongClickable) sb.append(", hold: true")
        if (node.isEditable) sb.append(", edit: true")
        if (node.isScrollable) sb.append(", scroll: true")
        if (node.isChecked) sb.append(", checked: true")

        val children = node.children
        if (children.isNotEmpty()) {
            sb.append(", ch: [\n")
            for ((index, child) in children.withIndex()) {
                sb.append(" ".repeat(indent + 2))
                sb.append("- ${nodeToYamlLine(child, indent + 2)}")
                if (index == children.lastIndex) {
                    sb.append("]")
                } else {
                    sb.append("\n")
                }
            }
        }

        if (node.moreCount > 0) {
            sb.append(", more: ${node.moreCount}")
        }

        sb.append("}")
        return sb.toString()
    }

    private fun quoteIfNeeded(text: String): String {
        return if (PruningRules.needsQuoting(text)) {
            "\"${text.replace("\"", "\\\"")}\""
        } else {
            text
        }
    }
}
