package com.niki914.zafiro.app.ui.content

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.base.UiStylePart
import com.niki914.uikit.dialog.ZafiroSingleChoiceDialog
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.ThemeController
import com.niki914.zafiro.app.ui.model.ThemeStylesIntent
import com.niki914.zafiro.app.ui.model.ThemeStylesUiState
import com.niki914.zafiro.app.ui.model.ThemeStylesViewModel

internal data class ThemeStyleOption(
    val style: ThemeStyle,
    val label: String,
)

@Composable
internal fun ThemeStylesDialogs(
    uiState: ThemeStylesUiState,
    viewModel: ThemeStylesViewModel,
    styleOptions: List<ThemeStyleOption>,
    unifiedStyle: ThemeStyle?,
) {
    // 统一风格单选弹窗
    ZafiroSingleChoiceDialog(
        visible = uiState.unifiedDialogVisible,
        onDismissRequest = { viewModel.sendIntent(ThemeStylesIntent.DismissUnifiedDialog) },
        title = stringResource(R.string.ui_theme_style_unified),
        options = styleOptions,
        selectedId = unifiedStyle?.storageKey,
        optionId = { it.style.storageKey },
        optionLabel = ThemeStyleOption::label,
        onSelect = { option ->
            viewModel.sendIntent(ThemeStylesIntent.SelectUnifiedStyle(option.style))
        },
    )

    // 单个部件单选弹窗
    val activePart = uiState.activePartDialog
    if (activePart != null) {
        val currentPartStyle = ThemeController.resolvedStyle(activePart)
        ZafiroSingleChoiceDialog(
            visible = true,
            onDismissRequest = { viewModel.sendIntent(ThemeStylesIntent.DismissPartDialog) },
            title = stringResource(partTitleRes(activePart)),
            options = styleOptions,
            selectedId = currentPartStyle.storageKey,
            optionId = { it.style.storageKey },
            optionLabel = ThemeStyleOption::label,
            onSelect = { option ->
                viewModel.sendIntent(ThemeStylesIntent.SelectPartStyle(activePart, option.style))
            },
        )
    }
}

private fun partTitleRes(part: UiStylePart): Int = when (part) {
    UiStylePart.TopBarPlate -> R.string.ui_theme_part_top_bar_plate
    UiStylePart.TopBarButtons -> R.string.ui_theme_part_top_bar_buttons
    UiStylePart.Button -> R.string.ui_theme_part_button
    UiStylePart.Slider -> R.string.ui_theme_part_slider
    UiStylePart.Switch -> R.string.ui_theme_part_switch
    UiStylePart.PageScroll -> R.string.ui_theme_part_page_scroll
    UiStylePart.Dialog -> R.string.ui_theme_part_dialog
    UiStylePart.Menu -> R.string.ui_theme_part_menu
    UiStylePart.TextField -> R.string.ui_theme_part_text_field
    UiStylePart.Settings -> R.string.ui_theme_part_settings
    UiStylePart.Composer -> R.string.ui_theme_part_composer
    UiStylePart.Search -> R.string.ui_theme_part_search
    UiStylePart.Loading -> R.string.ui_theme_part_loading
}
