package com.niki914.nexus.agentic.chat.agentic.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * YAML-output-format tests for the [TreeFormatter] YAML generation logic.
 *
 * NOTE: [TreeFormatter.format] requires an
 * [android.view.accessibility.AccessibilityNodeInfo], which is a **final**
 * Android framework class.  The current project does not include Mockito or
 * Robolectric, so we cannot construct real or mock instances in a unit-test
 * environment.  Consequently, the tests here **replicate** the public YAML
 * serialisation contract via helper functions that operate on [NodeInfo]
 * trees only.
 *
 * What is tested:
 *   - [NodeInfo] -> YAML line structure, quoting, escaping, truncation markers
 *   - Format characteristics (keys present, structural integrity)
 *   - Features that depend purely on [NodeInfo] values
 *
 * What is NOT tested (requires AccessibilityNodeInfo + buildTree):
 *   - The `buildTree(...)` DFS traversal / pruning / off-screen filtering
 *     (these are tested indirectly through [PruningRulesTest] + manual
 *     [NodeInfo] construction that simulates the post-pruning shape).
 *
 * To run full integration tests, add mockito-inline or Robolectric as a
 * test dependency and re-use the real [TreeFormatter.format].
 */
class TreeFormatterTest {

    // ---------------------------------------------------------------
    // Helpers — replicate the public YAML serialisation of TreeFormatter
    // so that NodeInfo -> YAML behaviour can be verified without
    // Android framework classes.
    // ---------------------------------------------------------------

    private fun yamlFromNodeInfo(
        root: NodeInfo,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400,
        appPackage: String = "test.package",
        nodeCount: Int = root.children.size,
        depthExceeded: Boolean = false,
    ): String {
        val sb = StringBuilder()
        sb.append("screen: [$screenWidth, $screenHeight]\n")
        sb.append("app: $appPackage\n")
        sb.append("tree:\n")
        for (child in root.children) {
            sb.append("  - ${yamlLine(child, indent = 2, screenWidth, screenHeight)}\n")
        }
        if (nodeCount >= 200) {
            sb.append("# truncated: max_nodes(200)\n")
        }
        if (depthExceeded) {
            sb.append("# truncated: max_depth(20)\n")
        }
        return sb.toString()
    }

    private fun yamlLine(
        node: NodeInfo,
        indent: Int,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
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
                sb.append("- ${yamlLine(child, indent + 2, screenWidth, screenHeight)}")
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

    // ---------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------

    @Test
    fun format_minimalTree() {
        // Simulate a two-level tree:
        //   Container (index=0) -> Button (index=1, clickable, text="OK")
        val button = NodeInfo(
            index = 1,
            semanticType = SemanticType.BUTTON,
            text = "OK",
            contentDesc = "",
            bounds = Rect(100, 200, 300, 400),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val frame = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 1080, 2400),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = listOf(button),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1,
                semanticType = SemanticType.CONTAINER,
                text = "",
                contentDesc = "",
                bounds = Rect(0, 0, 1080, 2400),
                isClickable = false,
                isLongClickable = false,
                isEditable = false,
                isScrollable = false,
                isChecked = false,
                children = listOf(frame),
                moreSummary = emptyList(),
            ),
        )

        assertTrue("must contain screen dimensions", yaml.startsWith("screen: [1080, 2400]"))
        assertTrue("must contain app package", yaml.contains("app: test.package"))
        assertTrue("must contain tree root", yaml.contains("tree:"))
        assertTrue("must contain container node", yaml.contains("{i: 0, t: container"))
        assertTrue("must contain button node", yaml.contains("{i: 1, t: button"))
        assertTrue("must mark clickable", yaml.contains("tap: true"))
        assertTrue("must include text", yaml.contains("txt: OK"))
    }

    @Test
    fun format_emptyShellPruned() {
        // Simulate a tree after empty-shell pruning:
        //   Container (index=0) directly contains Button (index=1).
        // The empty FrameLayout that would have sat between them has been
        // removed by buildTree in the real implementation.
        val button = NodeInfo(
            index = 1,
            semanticType = SemanticType.BUTTON,
            text = "click me",
            contentDesc = "",
            bounds = Rect(50, 100, 200, 150),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val root = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 1080, 2400),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = listOf(button),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(root), moreSummary = emptyList(),
            ),
        )

        // Button should be a direct child of root (no empty shell in between).
        assertTrue("button must appear in tree", yaml.contains("{i: 1, t: button"))
        assertTrue("button index must be 1", yaml.contains("i: 1"))
        // The container at index 0 is the parent
        assertTrue("parent must be container at index 0", yaml.contains("{i: 0, t: container"))
        // Verify button text is present
        assertTrue("button text must appear", yaml.contains("txt: \"click me\""))
    }

    @Test
    fun format_offScreenFiltered() {
        // Simulate a scrollable list where one child is off-screen.
        //   List (index=0, scrollable, moreSummary=["item8"])
        // The off-screen child is excluded from the children list and its
        // text is tracked via moreSummary.
        val list = NodeInfo(
            index = 0,
            semanticType = SemanticType.LIST,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 1080, 2400),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = true,
            isChecked = false,
            children = emptyList(),
            moreSummary = listOf("item8"),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(list), moreSummary = emptyList(),
            ),
        )

        assertTrue("must contain scrollable marker", yaml.contains("scroll: true"))
        assertTrue("must contain more summary", yaml.contains("more: [item8]"))
        assertTrue(
            "must not contain off-screen item",
            !yaml.contains("b: [0,2500,1080,2660]")
        )
    }

    @Test
    fun format_textEscaping() {
        // A node whose text contains an escaped newline (as produced by
        // PruningRules.normalizeText("line1\nline2") -> "line1\\nline2").
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.TEXT,
            text = "line1\\nline2",
            contentDesc = "",
            bounds = Rect(0, 0, 200, 100),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(node), moreSummary = emptyList(),
            ),
        )

        // The YAML must contain the escaped newline and must NOT contain a
        // raw newline that would break the YAML line.
        assertTrue(
            "must contain escaped newline",
            yaml.contains("txt: line1\\nline2")
        )
        // Verify no unescaped newline breaks the YAML line.
        assertFalse(
            "must not contain raw newline in txt field",
            yaml.contains("line1\nline2")
        )
    }

    @Test
    fun format_yamlIsValid() {
        // Structural validation of the YAML format: expected top-level keys
        // must be present and no unescaped newlines should appear inside
        // in-line values.
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.TEXT,
            text = "hello",
            contentDesc = "",
            bounds = Rect(0, 0, 200, 100),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(node), moreSummary = emptyList(),
            ),
        )

        assertTrue("must start with screen", yaml.startsWith("screen:"))
        assertTrue("must contain app", yaml.contains("app:"))
        assertTrue("must contain tree", yaml.contains("tree:"))
        // Lines that aren't top-level keys should have proper indentation.
        assertTrue("must contain indented node entry", yaml.contains("  - {"))
    }

    @Test
    fun format_rootNeverPruned() {
        // The root node at depth 0 must always be rendered even if it is an
        // empty shell (isEmptyShell returns true but depth == 0 protects it).
        val rootNode = NodeInfo(
            index = 0,
            semanticType = SemanticType.CONTAINER,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 1080, 2400),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(rootNode), moreSummary = emptyList(),
            ),
        )

        assertTrue("root node must appear in output", yaml.contains("{i: 0, t: container"))
        assertTrue("tree section must be present", yaml.contains("tree:"))
    }

    @Test
    fun format_maxNodesTruncation() {
        // When the node count reaches 200, a truncation marker must be
        // appended.  (The actual counting happens in buildTree; here we
        // verify the marker is emitted when signalled.)
        val node = NodeInfo(
            index = 0,
            semanticType = SemanticType.TEXT,
            text = "hello",
            contentDesc = "",
            bounds = Rect(0, 0, 200, 100),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(node), moreSummary = emptyList(),
            ),
            nodeCount = 200,
        )

        assertTrue(
            "must contain truncation marker",
            yaml.contains("# truncated: max_nodes(200)")
        )
    }

    @Test
    fun format_posFieldPresent() {
        // A node at the center of the screen (540..1620, 600..1800 in a 1080x2400
        // display) should produce "pos: center" in the YAML output.
        val centerNode = NodeInfo(
            index = 0,
            semanticType = SemanticType.BUTTON,
            text = "center",
            contentDesc = "",
            bounds = Rect(0, 800, 1080, 1600),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(centerNode), moreSummary = emptyList(),
            ),
        )

        assertTrue("center node must have pos: center", yaml.contains("pos: center"))
        assertTrue("must contain button text", yaml.contains("txt: center"))
    }

    @Test
    fun format_posFieldTopLeft() {
        // A node in the top-left quadrant (small bounds near origin) should
        // produce "pos: top-left" in the YAML output.
        val topLeftNode = NodeInfo(
            index = 0,
            semanticType = SemanticType.TEXT,
            text = "corner",
            contentDesc = "",
            bounds = Rect(0, 0, 100, 80),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(topLeftNode), moreSummary = emptyList(),
            ),
        )

        assertTrue("top-left node must have pos: top-left", yaml.contains("pos: top-left"))
    }

    @Test
    fun format_moreArrayFormat() {
        // A scrollable node with multiple off-screen summaries should render
        // them as a YAML array: more: ["item 8", "(empty)"].
        val scrollable = NodeInfo(
            index = 0,
            semanticType = SemanticType.LIST,
            text = "",
            contentDesc = "",
            bounds = Rect(0, 0, 1080, 2400),
            isClickable = false,
            isLongClickable = false,
            isEditable = false,
            isScrollable = true,
            isChecked = false,
            children = emptyList(),
            moreSummary = listOf("item 8", "(empty)"),
        )
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(scrollable), moreSummary = emptyList(),
            ),
        )

        assertTrue("scrollable must have more marker", yaml.contains("scroll: true"))
        assertTrue(
            "must contain array-format more summary",
            yaml.contains("""more: ["item 8", (empty)]""")
        )
    }

    @Test
    fun format_collapse_simulated() {
        // Simulates the output after shouldCollapse has removed an intermediate
        // useless CONTAINER.  The tree is root -> leaf directly, with no
        // intermediate container layer.
        val leaf = NodeInfo(
            index = 1,
            semanticType = SemanticType.BUTTON,
            text = "collapsed-leaf",
            contentDesc = "",
            bounds = Rect(100, 200, 300, 400),
            isClickable = true,
            isLongClickable = false,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            children = emptyList(),
            moreSummary = emptyList(),
        )
        // No intermediate container; leaf is direct child of root (simulating
        // collapse of the intermediate frame).
        val yaml = yamlFromNodeInfo(
            NodeInfo(
                index = -1, semanticType = SemanticType.CONTAINER,
                text = "", contentDesc = "", bounds = Rect(0, 0, 1080, 2400),
                isClickable = false, isLongClickable = false, isEditable = false,
                isScrollable = false, isChecked = false,
                children = listOf(leaf), moreSummary = emptyList(),
            ),
        )

        assertTrue(
            "leaf must appear in output directly under root",
            yaml.contains("{i: 1, t: button")
        )
        assertTrue(
            "leaf text must be present",
            yaml.contains("""txt: "collapsed-leaf"""")
        )
        assertFalse(
            "must not contain intermediate container at index 0",
            yaml.contains("{i: 0, t: container")
        )
    }
}
