package com.niki914.nexus.agentic.chat.agentic.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.Rect as AndroidRect

object TreeFormatter {

    fun format(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        version: String,
    ): FormattedTree {
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
            moreSummary = emptyList(),
        )
        return FormattedTree(
            yaml = toYaml(rootNode, screenWidth, screenHeight, appPackage, version, nodeCounter, depthExceeded),
            semanticFingerprint = computeSemanticFingerprint(
                rootNode,
                screenWidth,
                screenHeight,
                appPackage,
                truncatedMaxNodes = nodeCounter.get() >= 200,
                truncatedMaxDepth = depthExceeded.get(),
            ),
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

    private fun toYaml(
        node: NodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        version: String,
        nodeCounter: AtomicInteger,
        depthExceeded: AtomicBoolean,
    ): String {
        val sb = StringBuilder()
        sb.append("screen: [$screenWidth, $screenHeight]\n")
        sb.append("app: $appPackage\n")
        sb.append("version: \"$version\"\n")
        sb.append("tree:\n")
        for (child in node.children) {
            sb.append("  - ${nodeToYamlLine(child, indent = 2, screenWidth, screenHeight, version)}\n")
        }
        if (nodeCounter.get() >= 200) {
            sb.append("# truncated: max_nodes(200)\n")
        }
        if (depthExceeded.get()) {
            sb.append("# truncated: max_depth(20)\n")
        }
        return sb.toString()
    }

    private fun nodeToYamlLine(
        node: NodeInfo,
        indent: Int,
        screenWidth: Int,
        screenHeight: Int,
        version: String,
    ): String {
        val sb = StringBuilder()
        sb.append(
            "{i: ${node.index}, t: ${node.semanticType.name.lowercase()}, b: [${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}], pos: ${
                PruningRules.posOf(
                    node.bounds,
                    screenWidth,
                    screenHeight
                )
            }"
        )

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
                sb.append("- ${nodeToYamlLine(child, indent + 2, screenWidth, screenHeight, version)}")
                if (index == children.lastIndex) {
                    sb.append("]")
                } else {
                    sb.append("\n")
                }
            }
        }

        if (node.moreSummary.isNotEmpty()) {
            sb.append(", more: [${node.moreSummary.joinToString(", ") { quoteIfNeeded(it) }}]")
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

    /**
     * Deterministic 64-bit FNV-1a fingerprint over the pruned tree's semantic
     * fields plus screen size, app package and truncation flags.
     *
     * Must stay in sync with [toYaml]: every field that can reach the YAML
     * output is mixed in here, and fields the YAML ignores (index, version,
     * raw class names) must not be. Traversal follows [toYaml]'s DFS order.
     */
    internal fun computeSemanticFingerprint(
        root: NodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        truncatedMaxNodes: Boolean,
        truncatedMaxDepth: Boolean,
    ): Long {
        var seed = FNV_OFFSET_BASIS
        seed = mixLong(seed, screenWidth.toLong())
        seed = mixLong(seed, screenHeight.toLong())
        seed = mixString(seed, appPackage)
        seed = mixNode(seed, root)
        seed = mixLong(seed, if (truncatedMaxNodes) 1L else 0L)
        seed = mixLong(seed, if (truncatedMaxDepth) 1L else 0L)
        return seed
    }

    private fun mixNode(seed: Long, node: NodeInfo): Long {
        var s = mixString(seed, node.semanticType.name)
        s = mixString(s, node.text)
        s = mixString(s, node.contentDesc)
        s = mixLong(s, node.bounds.left.toLong())
        s = mixLong(s, node.bounds.top.toLong())
        s = mixLong(s, node.bounds.right.toLong())
        s = mixLong(s, node.bounds.bottom.toLong())
        s = mixLong(s, if (node.isClickable) 1L else 0L)
        s = mixLong(s, if (node.isLongClickable) 1L else 0L)
        s = mixLong(s, if (node.isEditable) 1L else 0L)
        s = mixLong(s, if (node.isScrollable) 1L else 0L)
        s = mixLong(s, if (node.isChecked) 1L else 0L)
        s = mixString(s, node.moreSummary.joinToString(", "))
        for (child in node.children) {
            s = mixNode(s, child)
        }
        return s
    }

    private fun mixLong(seed: Long, value: Long): Long = (seed xor value) * FNV_PRIME

    private fun mixString(seed: Long, value: String): Long {
        var s = seed
        for (c in value) {
            s = (s xor c.code.toLong()) * FNV_PRIME
        }
        return s
    }

    // 0xcbf29ce484222325 (14695981039346656037) exceeds Long.MAX_VALUE, so it
    // is written as its two's-complement form: the bit pattern is identical
    // and FNV-1a arithmetic is unaffected.
    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
}

/**
 * Result of a single formatting pass: the model-visible YAML and a
 * deterministic 64-bit semantic fingerprint over the same pruned tree.
 *
 * Public because it is the return type of the public [TreeFormatter.format]
 * (same pattern as [ScreenSnapshot]).
 */
data class FormattedTree(
    val yaml: String,
    val semanticFingerprint: Long,
)
