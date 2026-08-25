package com.niki914.zafiro.app.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import com.niki914.zafiro.app.R

/**
 * 折叠块图标目录。Thinking 用固定图标；工具按 name 分派，
 * Python 走自绘 drawable，未知工具/MCP 回退扳手。
 */
object ToolIcons {
    /** Thinking 图标（TODO New：用户待定，暂用四角星星）。 */
    val Thinking = Icons.Filled.AutoAwesome

    private val Terminal = Icons.Filled.Terminal
    private val Skill = Icons.AutoMirrored.Filled.MenuBook

    /** 默认（兜底）图标：扳手。 */
    val Default = Icons.Filled.Build

    /** 多工具链头部图标：堆叠层（候选 ViewList 可一行换）。 */
    val Multi = Icons.Filled.Layers

    @Composable
    fun forTool(name: String): ImageVector {
        val python = ImageVector.vectorResource(R.drawable.python_logo)
        return when {
            name.contains("python", ignoreCase = true) -> python
            name.contains("terminal", ignoreCase = true) || name.contains("shell", ignoreCase = true) -> Terminal
            name.contains("skill", ignoreCase = true) -> Skill
            else -> Default
        }
    }
}