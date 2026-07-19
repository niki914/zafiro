package com.niki914.nexus.agentic.chat.agentic.accessibility

enum class SemanticType {
    BUTTON, INPUT, TEXT, IMAGE,
    LIST, LIST_ITEM,
    SWITCH, CHECKBOX,
    TAB, CHIP,
    TOOLBAR, DIALOG,
    CONTAINER,
}

data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class NodeInfo(
    val index: Int,
    val semanticType: SemanticType,
    val text: String,
    val contentDesc: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isLongClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val children: List<NodeInfo>,
    val moreSummary: List<String> = emptyList(),
)
