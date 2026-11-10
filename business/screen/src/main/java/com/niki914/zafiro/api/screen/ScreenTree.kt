package com.niki914.zafiro.api.screen

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
)

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

data class SemanticToken(val version: String, val index: Int)

data class ScreenSnapshot(
    val screen: ScreenId,
    val version: String,
    val width: Int,
    val height: Int,
    val url: String?,
    val nodes: List<NodeInfo>,
    val yaml: String,
)

class ImageFrame(
    val screen: ScreenId,
    val width: Int,
    val height: Int,
    val png: ByteArray,
)
