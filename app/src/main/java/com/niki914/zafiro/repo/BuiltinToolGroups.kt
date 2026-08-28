package com.niki914.zafiro.repo

import androidx.annotation.StringRes
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry

enum class BuiltinToolGroupMode {
    // 绑定式：一级页 Switch 写穿全组成员，成员无独立入口
    WHOLE,

    // 不绑定：一级页导航行，二级页逐工具开关
    PER_TOOL,
}

data class BuiltinToolGroup(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val summaryRes: Int,
    val mode: BuiltinToolGroupMode,
    val members: List<String>,
)

// 组是纯展示与批量编辑的代码内元数据：id 永不落盘，落盘只有 per-tool 布尔，
// 因此增删成员、调整分组、组改名对持久化配置零影响。
object BuiltinToolGroups {
    private val registry = BuiltinToolRegistry.default()

    val all: List<BuiltinToolGroup> = listOf(
        BuiltinToolGroup(
            id = "dev_tools",
            titleRes = R.string.builtin_tool_group_dev_tools,
            summaryRes = R.string.builtin_tool_group_dev_tools_summary,
            mode = BuiltinToolGroupMode.PER_TOOL,
            members = listOf("terminal", "execute_python", "py_meta_tools"),
        ),
        BuiltinToolGroup(
            id = "android_native",
            titleRes = R.string.builtin_tool_group_android_native,
            summaryRes = R.string.builtin_tool_group_android_native_summary,
            mode = BuiltinToolGroupMode.PER_TOOL,
            members = listOf("open_uri", "launch_app", "search_apps", "notify"),
        ),
        BuiltinToolGroup(
            id = "screen_operation",
            titleRes = R.string.builtin_tool_group_screen_operation,
            summaryRes = R.string.builtin_tool_group_screen_operation_summary,
            mode = BuiltinToolGroupMode.WHOLE,
            members = listOf(
                "screen_operation_accessibility",
                "screen_operation_shell",
            ),
        ),
    )

    init {
        require(all.map { it.id }.toSet().size == all.size) { "Duplicate builtin tool group id." }
        val groupedNames = all.flatMap { it.members }
        require(groupedNames.toSet().size == groupedNames.size) {
            "Builtin tool assigned to multiple groups."
        }
        all.forEach { group ->
            require(group.members.isNotEmpty()) { "Empty builtin tool group ${group.id}." }
            group.members.forEach { member ->
                require(registry.find(member) != null) {
                    "Builtin tool group ${group.id} references unknown tool $member."
                }
            }
        }
    }

    fun find(id: String): BuiltinToolGroup? = all.firstOrNull { it.id == id }

    fun isGrouped(toolName: String): Boolean =
        all.any { it.members.contains(toolName) }
}
