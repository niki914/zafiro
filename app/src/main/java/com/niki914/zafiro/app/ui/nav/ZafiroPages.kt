package com.niki914.zafiro.app.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import com.niki914.uikit.infra.nav.Page
import com.niki914.zafiro.app.R
import com.niki914.zafiro.repo.BuiltinToolGroups

sealed interface PageTitleSpec

data object NoTitle : PageTitleSpec

data class ResTitle(
    @StringRes val resId: Int,
) : PageTitleSpec

data class TextTitle(
    val value: String,
) : PageTitleSpec

data class TopBarActionSpec(
    val icon: ImageVector,
    val onClick: (() -> Unit)? = null,
    val contentDescription: String? = null,
)

sealed interface ZafiroPage : Page {
    val titleSpec: PageTitleSpec
    val leftAction: TopBarActionSpec?
    val rightAction: TopBarActionSpec?
    val showBlurLayer: Boolean
        get() = true

    /**
     * 顶栏标题模式：Pinned = 小标题常驻（到顶时背景透明但标题照常显示）；
     * Collapsible = 大标题在内容顶部，随滚动切换为小标题（设置 spec 页）。
     */
    val titleMode: TitleBarMode
        get() = TitleBarMode.Pinned

    /**
     * 是否允许系统返回键驱动页面回退。false 时返回键不触发 pop，
     * 用于 onboarding 入口页防止退回动画页。
     */
    val backEnabled: Boolean
        get() = true
}

enum class TitleBarMode { Pinned, Collapsible }

data object StartupPage : ZafiroPage {
    override val routeKey: String = "startup"
    override val titleSpec: PageTitleSpec = NoTitle
    override val leftAction: TopBarActionSpec? = null
    override val rightAction: TopBarActionSpec? = null
    override val showBlurLayer: Boolean = false
}

data object ProviderPickPage : ZafiroPage {
    override val routeKey: String = "provider-pick"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.ui_onboard_provider_pick_title)

    // onboarding 首次流程不可返回 StartupPage：隐藏左上角按钮 + 拦截返回键。
    override val leftAction: TopBarActionSpec? = null
    override val rightAction: TopBarActionSpec? = null
    override val backEnabled: Boolean = false
}

data object SettingsProviderPickPage : ZafiroPage {
    override val routeKey: String = "settings-provider-pick"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.ui_onboard_provider_pick_title)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
}

data class ConfigurePage(
    val providerId: String? = null,
    val explicitTitleSpec: PageTitleSpec? = null,
) : ZafiroPage {
    override val routeKey: String = if (providerId == null) "configure" else "configure:$providerId"
    override val titleSpec: PageTitleSpec =
        explicitTitleSpec ?: ResTitle(R.string.ui_onboard_configure_title)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
}

data class SavedConfigDetailPage(
    /** null = 新建（品牌选择后进入）。 */
    val configId: String?,
    val configName: String,
    val providerId: String? = null,
) : ZafiroPage {
    val isCreating: Boolean
        get() = configId == null
    override val routeKey: String = "saved-config-detail:${configId ?: providerId ?: "new"}"
    override val titleSpec: PageTitleSpec = TextTitle(configName)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)

    // Delete 由内容层 PageChrome 提供（生效中的配置不提供删除）
    override val rightAction: TopBarActionSpec? = null
}

data object DonePage : ZafiroPage {
    override val routeKey: String = "done"
    override val titleSpec: PageTitleSpec = NoTitle
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
}

data object HomePage : ZafiroPage {
    override val routeKey: String = "home"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.ui_home_title)
    override val leftAction: TopBarActionSpec? = null
    override val rightAction: TopBarActionSpec = TopBarActionSpec(
        icon = Icons.Default.MoreHoriz,
    )
}

data object ConversationHistoryPage : ZafiroPage {
    override val routeKey: String = "conversation-history"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.ui_home_title)
    override val leftAction: TopBarActionSpec? = null
    override val rightAction: TopBarActionSpec? = null
}

data object ThemeSettingsPage : ZafiroPage {
    override val routeKey: String = "theme-settings"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.ui_settings_appearance)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
    override val titleMode: TitleBarMode = TitleBarMode.Collapsible
}

data object SettingsHomePage : ZafiroPage {
    override val routeKey: String = "settings-home"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.ui_settings_title)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
    override val titleMode: TitleBarMode = TitleBarMode.Collapsible
}

data class SettingsDetailPage(
    val group: ZafiroSettingsGroup,
    val explicitTitleSpec: PageTitleSpec? = null,
) : ZafiroPage {
    override val routeKey: String = "settings-detail:${group.routeSuffix}"
    override val titleSpec: PageTitleSpec = explicitTitleSpec ?: ResTitle(group.titleRes)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null

    // 全部组均渲染大标题页（ModelConfig 已迁移到 SettingsListPageContent）。
    override val titleMode: TitleBarMode = TitleBarMode.Collapsible
}

data class McpServerDetailPage(
    val serverName: String,
    val serverIndex: Int,
    val isCreating: Boolean = false,
) : ZafiroPage {
    override val routeKey: String = "mcp-server-detail:$serverIndex:$serverName"
    override val titleSpec: PageTitleSpec = TextTitle(serverName)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? =
        if (isCreating) null else TopBarActionSpec(Icons.Default.Delete)
}

data class ExecutionRuleDetailPage(
    val ruleName: String,
    val ruleIndex: Int,
    val isCreating: Boolean = false,
) : ZafiroPage {
    override val routeKey: String = "execution-rule-detail:$ruleIndex:$ruleName"
    override val titleSpec: PageTitleSpec = TextTitle(ruleName)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
}

data class TakeoverRuleDetailPage(
    val ruleId: String?,
    val ruleName: String,
    val ruleIndex: Int,
    val isCreating: Boolean = false,
) : ZafiroPage {
    override val routeKey: String = "takeover-rule-detail:${ruleId ?: "new"}:$ruleIndex:$ruleName"
    override val titleSpec: PageTitleSpec = TextTitle(ruleName)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? =
        if (isCreating) null else TopBarActionSpec(Icons.Default.Delete)
}

data object CustomPyToolsPage : ZafiroPage {
    override val routeKey: String = "custom-py-tools"
    override val titleSpec: PageTitleSpec = ResTitle(R.string.custom_py_tool_page_title)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
}

data class CustomPyToolDetailPage(
    val toolName: String,
    val toolIndex: Int,
    val isCreating: Boolean = false,
) : ZafiroPage {
    override val routeKey: String = "custom-py-tool-detail:$toolIndex:$toolName"
    override val titleSpec: PageTitleSpec = TextTitle(toolName)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? =
        if (isCreating) null else TopBarActionSpec(Icons.Default.Delete)
}

data class SkillDetailPage(
    val skillId: String,
    val skillTitle: String,
) : ZafiroPage {
    override val routeKey: String = "skill-detail:$skillId"
    override val titleSpec: PageTitleSpec = TextTitle(skillTitle)
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = TopBarActionSpec(Icons.Default.Delete)
}

data class BuiltinToolGroupDetailPage(
    val groupId: String,
) : ZafiroPage {
    override val routeKey: String = "builtin-tool-group:$groupId"
    override val titleSpec: PageTitleSpec = ResTitle(
        BuiltinToolGroups.find(groupId)?.titleRes
            ?: R.string.ui_settings_builtin_tools
    )
    override val leftAction: TopBarActionSpec =
        TopBarActionSpec(Icons.AutoMirrored.Filled.ArrowBack)
    override val rightAction: TopBarActionSpec? = null
    override val titleMode: TitleBarMode = TitleBarMode.Collapsible
}
