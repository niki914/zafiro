package com.niki914.zafiro.app.ui.content

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStylePart
import com.niki914.uikit.button.ZafiroButton
import com.niki914.uikit.button.ZafiroTonalButton
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.control.ZafiroSlider
import com.niki914.uikit.control.ZafiroSwitch
import com.niki914.uikit.infra.LiquidScreenTitle
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.component.SettingsConnectedGroup
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot
import com.niki914.uikit.infra.component.settings.item.SettingsTokens
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.uikit.screen.TitleDirection
import com.niki914.uikit.screen.ZafiroTopBar
import com.niki914.uikit.screen.ZafiroTopBarDefaults
import com.niki914.uikit.textfield.ZafiroOutlinedTextField
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.home.composer.ZafiroChatComposer
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.model.ThemeStylesIntent
import com.niki914.zafiro.app.ui.model.ThemeStylesViewModel

@Composable
fun ThemeStylesSettingsContent(
    viewModel: ThemeStylesViewModel = pageViewModel(),
) {
    val uiState by viewModel.uiStateFlow.collectAsState()

    val styleOptions = listOf(
        ThemeStyleOption(ThemeStyle.LiquidGlass, stringResource(R.string.ui_theme_style_liquid)),
        ThemeStyleOption(ThemeStyle.Material3, stringResource(R.string.ui_theme_style_md3)),
        ThemeStyleOption(ThemeStyle.Material3Expressive, stringResource(R.string.ui_theme_style_md3e)),
    )

    @Composable
    fun styleLabel(style: ThemeStyle): String = when (style) {
        ThemeStyle.LiquidGlass -> stringResource(R.string.ui_theme_style_liquid)
        ThemeStyle.Material3 -> stringResource(R.string.ui_theme_style_md3)
        ThemeStyle.Material3Expressive -> stringResource(R.string.ui_theme_style_md3e)
    }

    val unifiedStyle = ThemeController.unifiedThemeStyleOrNull()
    val unifiedLabel = unifiedStyle?.let { styleLabel(it) }
        ?: stringResource(R.string.ui_theme_style_custom)

    @Composable
    fun partStyleLabel(part: UiStylePart): String =
        styleLabel(ThemeController.resolvedStyle(part))

    CompositionLocalProvider(
        LocalScreenBackdrop provides null,
    ) {
        SettingsListPageContent {
            // 1. 顶部置顶项：统一风格
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    SettingCardItem(
                        title = stringResource(R.string.ui_theme_style_unified),
                        summary = null,
                        trailingSlot = SettingTrailingSlot.Preview(
                            text = unifiedLabel,
                            showChevron = true,
                        ),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenUnifiedDialog) },
                    )
                }
            }

            BareComponentDivider()

            // 2. 顶栏背板与顶栏按钮配置项
            SettingsConnectedGroup(title = stringResource(R.string.ui_theme_group_layout)) {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_top_bar_plate),
                        currentStyleText = partStyleLabel(UiStylePart.TopBarPlate),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.TopBarPlate)) },
                    )
                }
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_top_bar_buttons),
                        currentStyleText = partStyleLabel(UiStylePart.TopBarButtons),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.TopBarButtons)) },
                    )
                }
            }

            // 3. 搜索框 Preview（接入标准顶栏转场动效）
            BareComponentDivider()

            var searchQuery by remember { mutableStateOf("") }
            var searchActive by remember { mutableStateOf(false) }

            val searchBarTitle = remember(searchActive, searchQuery) {
                LiquidScreenTitle.Content(
                    key = "theme_styles_search_bar",
                    content = {
                        ZafiroSearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            isActive = searchActive,
                            onActiveChange = { searchActive = it },
                            onClearQuery = { searchQuery = "" },
                            onExitSearch = {
                                searchActive = false
                                searchQuery = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }

            ZafiroTopBar(
                title = searchBarTitle,
                titleDirection = TitleDirection.None,
                baseTitleAlpha = 1f,
                topInset = 0.dp,
                leftButton = if (!searchActive) {
                    {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                } else null,
                rightButton = if (!searchActive) {
                    {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                        )
                    }
                } else null,
                onLeftClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.TopBarButtons)) },
                onRightClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.TopBarButtons)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ZafiroTopBarDefaults.ButtonSlotHeight),
            )

            // 搜索栏配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_search),
                        currentStyleText = partStyleLabel(UiStylePart.Search),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Search)) },
                    )
                }
            }

            // 4. 聊天输入栏（裸组件撑满宽度）
            BareComponentDivider()

            var composerText by remember { mutableStateOf("") }
            ZafiroChatComposer(
                value = composerText,
                onValueChange = { composerText = it },
                onSendClick = {},
                onStopClick = {},
                isGenerating = false,
                modifier = Modifier.fillMaxWidth(),
            )

            // 聊天输入栏配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_composer),
                        currentStyleText = partStyleLabel(UiStylePart.Composer),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Composer)) },
                    )
                }
            }

            // 5. 开关（裸组件居中）
            BareComponentDivider()

            var switchChecked by remember { mutableStateOf(true) }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ZafiroSwitch(
                    checked = switchChecked,
                    onCheckedChange = { switchChecked = it },
                )
            }

            // 开关配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_switch),
                        currentStyleText = partStyleLabel(UiStylePart.Switch),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Switch)) },
                    )
                }
            }

            // 6. 滑块（裸组件撑满宽度）
            BareComponentDivider()

            var sliderValue by remember { mutableFloatStateOf(0.6f) }
            ZafiroSlider(
                value = { sliderValue },
                onValueChange = { sliderValue = it },
                modifier = Modifier.fillMaxWidth(),
            )

            // 滑块配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_slider),
                        currentStyleText = partStyleLabel(UiStylePart.Slider),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Slider)) },
                    )
                }
            }

            // 7. 输入框（裸组件撑满宽度）
            BareComponentDivider()

            var sampleText by remember { mutableStateOf("Zafiro") }
            ZafiroOutlinedTextField(
                value = sampleText,
                onValueChange = { sampleText = it },
                placeholder = stringResource(R.string.ui_theme_preview_input_hint),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // 输入框配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_text_field),
                        currentStyleText = partStyleLabel(UiStylePart.TextField),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.TextField)) },
                    )
                }
            }

            // 8. 按钮组（裸组件撑满宽度）
            BareComponentDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZafiroButton(
                    text = stringResource(R.string.ui_theme_preview_primary_btn),
                    onClick = {},
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
                ZafiroTonalButton(
                    text = stringResource(R.string.ui_theme_preview_tonal_btn),
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }

            // 按钮配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_button),
                        currentStyleText = partStyleLabel(UiStylePart.Button),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Button)) },
                    )
                }
            }

            // 9. 加载动画（裸组件居中）
            BareComponentDivider()

            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ZafiroLoadingIndicator(size = 36.dp)
            }

            // 加载指示器配置项（单卡）
            SettingsConnectedGroup {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_loading),
                        currentStyleText = partStyleLabel(UiStylePart.Loading),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Loading)) },
                    )
                }
            }

            BareComponentDivider()

            // 10. 动效与系统行为
            SettingsConnectedGroup(title = stringResource(R.string.ui_theme_group_system)) {
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_page_scroll),
                        currentStyleText = partStyleLabel(UiStylePart.PageScroll),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.PageScroll)) },
                    )
                }
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_dialog),
                        currentStyleText = partStyleLabel(UiStylePart.Dialog),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Dialog)) },
                    )
                }
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_menu),
                        currentStyleText = partStyleLabel(UiStylePart.Menu),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Menu)) },
                    )
                }
                item { shape, isStandalone, _ ->
                    ComponentSettingRow(
                        title = stringResource(R.string.ui_theme_part_settings),
                        currentStyleText = partStyleLabel(UiStylePart.Settings),
                        shape = shape,
                        isStandaloneCard = isStandalone,
                        onClick = { viewModel.sendIntent(ThemeStylesIntent.OpenPartDialog(UiStylePart.Settings)) },
                    )
                }
            }
        }
    }

    ThemeStylesDialogs(
        uiState = uiState,
        viewModel = viewModel,
        styleOptions = styleOptions,
        unifiedStyle = unifiedStyle,
    )
}

@Composable
private fun BareComponentDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun ComponentSettingRow(
    title: String,
    currentStyleText: String,
    onClick: () -> Unit,
    shape: Shape = G2CardShape(SettingsTokens.CardCornerRadius),
    isStandaloneCard: Boolean = false,
) {
    SettingCardItem(
        title = title,
        summary = null,
        trailingSlot = SettingTrailingSlot.Preview(
            text = currentStyleText,
            showChevron = true,
        ),
        shape = shape,
        isStandaloneCard = isStandaloneCard,
        onClick = onClick,
    )
}

