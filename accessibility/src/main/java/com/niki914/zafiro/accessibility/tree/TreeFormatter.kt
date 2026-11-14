package com.niki914.zafiro.accessibility.tree

import com.niki914.zafiro.api.screen.NodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 将结构化的 [NodeInfo] 树序列化为 LLM 高效消费的紧凑 YAML 字符串。
 */
object TreeFormatter {

    fun toYaml(
        root: NodeInfo,
        screenWidth: Int,
        screenHeight: Int,
        appPackage: String,
        version: String,
        totalNodesCount: Int,
        depthExceeded: Boolean,
    ): String {
        val sb = StringBuilder()
        sb.append("screen: [$screenWidth, $screenHeight]\n")
        sb.append("app: $appPackage\n")
        sb.append("version: \"$version\"\n")
        sb.append("tree:\n")
        for (child in root.children) {
            sb.append("  - ${nodeToYamlLine(child, indent = 2, screenWidth, screenHeight, version)}\n")
        }
        if (totalNodesCount >= 200) {
            sb.append("# truncated: max_nodes(200)\n")
        }
        if (depthExceeded) {
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
                PruningRules.posOf(node.bounds, screenWidth, screenHeight)
            }"
        )

        if (node.text.isNotEmpty()) {
            sb.append(", txt: ${quoteIfNeeded(PruningRules.normalizeText(node.text))}")
        }

        if (node.contentDesc.isNotEmpty()) {
            sb.append(", h: ${quoteIfNeeded(PruningRules.normalizeText(node.contentDesc))}")
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
}
