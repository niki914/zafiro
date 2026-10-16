package com.niki914.uikit.infra.component.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingExpandableSliderItem
import com.niki914.uikit.infra.component.SettingToggleItem
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListItem
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.SettingsToggleListItemCard

@Composable
fun SettingsSpecPageContent(
    spec: SettingsPageSpec,
    modifier: Modifier = Modifier,
    /** 内容区底部安全距离。null = 走壳层下发的 `liquidScreenBottomPadding()`；吸底按钮的页面传入加大值。 */
    contentBottomPadding: Dp? = null,
    contentBeforeSections: @Composable ColumnScope.() -> Unit = {},
    contentAfterSections: @Composable ColumnScope.() -> Unit = {},
    onAction: (SettingsRowAction) -> Unit,
) {
    var expandedSliderId by rememberSaveable {
        mutableStateOf(
            spec.sections.flatMap { it.rows }
                .filterIsInstance<SettingsRowSpec.Slider>()
                .firstOrNull { it.initiallyExpanded }
                ?.id
        )
    }

    SettingsListPageContent(
        description = spec.description,
        modifier = modifier,
        contentBottomPadding = contentBottomPadding,
        onBackgroundTap = { expandedSliderId = null },
    ) {
        contentBeforeSections()
        spec.sections.forEach { section ->
            SettingsSectionContent(
                section = section,
                expandedSliderId = expandedSliderId,
                onExpandedSliderChange = { expandedSliderId = it },
                onAction = { action ->
                    if (action !is SettingsRowAction.SliderChanged && action !is SettingsRowAction.SliderCommit) {
                        expandedSliderId = null
                    }
                    onAction(action)
                },
            )
        }
        contentAfterSections()
    }
}

@Composable
private fun SettingsSectionContent(
    section: SettingsSectionSpec,
    expandedSliderId: String?,
    onExpandedSliderChange: (String?) -> Unit,
    onAction: (SettingsRowAction) -> Unit,
) {
    if (section.rows.isEmpty()) {
        return
    }

    when (section.layout) {
        SettingsSectionLayout.GroupedCard -> {
            SettingsGroupCard(title = section.title) {
                section.rows.forEachIndexed { index, row ->
                    SettingsRowContent(
                        row = row,
                        layout = section.layout,
                        expandedSliderId = expandedSliderId,
                        onExpandedSliderChange = onExpandedSliderChange,
                        onAction = onAction,
                    )
                    if (index != section.rows.lastIndex) {
                        SettingsItemDivider()
                    }
                }
            }
        }

        SettingsSectionLayout.CardList -> {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                section.rows.forEach { row ->
                    SettingsCardListRowContent(
                        row = row,
                        expandedSliderId = expandedSliderId,
                        onExpandedSliderChange = onExpandedSliderChange,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsCardListRowContent(
    row: SettingsRowSpec,
    expandedSliderId: String?,
    onExpandedSliderChange: (String?) -> Unit,
    onAction: (SettingsRowAction) -> Unit,
) {
    if (row is SettingsRowSpec.ToggleNavigation) {
        SettingsRowContent(
            row = row,
            layout = SettingsSectionLayout.CardList,
            expandedSliderId = expandedSliderId,
            onExpandedSliderChange = onExpandedSliderChange,
            onAction = onAction,
        )
    } else {
        SettingsGroupCard {
            SettingsRowContent(
                row = row,
                layout = SettingsSectionLayout.CardList,
                expandedSliderId = expandedSliderId,
                onExpandedSliderChange = onExpandedSliderChange,
                onAction = onAction,
            )
        }
    }
}

@Composable
private fun SettingsRowContent(
    row: SettingsRowSpec,
    layout: SettingsSectionLayout,
    expandedSliderId: String?,
    onExpandedSliderChange: (String?) -> Unit,
    onAction: (SettingsRowAction) -> Unit,
) {
    when (row) {
        is SettingsRowSpec.Navigation -> SettingsListItem(
            title = row.title,
            summary = row.summary,
            currentState = row.currentState,
            enabled = row.enabled,
            showChevron = true,
            leadingContent = row.leadingIcon?.let { icon ->
                { SettingsRowLeadingIconContent(icon) }
            },
            onClick = { onAction(SettingsRowAction.Navigate(row.id)) },
        )

        is SettingsRowSpec.Toggle -> SettingToggleItem(
            title = row.title,
            description = row.summary,
            checked = row.checked,
            enabled = row.enabled,
            onCheckedChange = { checked ->
                onAction(SettingsRowAction.ToggleChanged(row.id, checked))
            },
        )

        is SettingsRowSpec.ToggleNavigation -> {
            require(layout == SettingsSectionLayout.CardList) {
                "SettingsRowSpec.ToggleNavigation requires SettingsSectionLayout.CardList."
            }
            SettingsToggleListItemCard(
                title = row.title,
                summary = row.summary,
                checked = row.checked,
                enabled = row.enabled,
                onCheckedChange = { checked ->
                    onAction(SettingsRowAction.ToggleChanged(row.id, checked))
                },
                onClick = { onAction(SettingsRowAction.Navigate(row.id)) },
            )
        }

        is SettingsRowSpec.Value -> SettingsListItem(
            title = row.title,
            summary = row.summary,
            currentState = row.currentState,
            enabled = row.enabled,
            leadingContent = row.leadingIcon?.let { icon ->
                { SettingsRowLeadingIconContent(icon) }
            },
            onClick = null,
        )

        is SettingsRowSpec.Action -> SettingsListItem(
            title = row.title,
            summary = row.summary,
            enabled = row.enabled,
            onClick = { onAction(SettingsRowAction.Click(row.id)) },
        )

        is SettingsRowSpec.Destructive -> SettingsListItem(
            title = row.title,
            summary = row.summary,
            enabled = row.enabled,
            onClick = { onAction(SettingsRowAction.Click(row.id)) },
        )

        is SettingsRowSpec.Message -> SettingsMessageItem(
            text = row.title,
            horizontalPadding = row.horizontalPadding,
            verticalPadding = row.verticalPadding,
        )

        is SettingsRowSpec.Slider -> SettingExpandableSliderItem(
            title = row.title,
            summary = row.summary,
            value = row.value,
            valueRange = row.valueRange,
            steps = row.steps,
            valueLabel = row.valueLabel,
            startLabel = row.startLabel,
            endLabel = row.endLabel,
            enabled = row.enabled,
            expanded = expandedSliderId == row.id,
            onExpandedChange = { expanded ->
                onExpandedSliderChange(if (expanded) row.id else null)
            },
            onValueChange = { onAction(SettingsRowAction.SliderChanged(row.id, it)) },
            onValueCommit = { onAction(SettingsRowAction.SliderCommit(row.id, it)) },
        )
    }
}

@Composable
private fun SettingsRowLeadingIconContent(icon: SettingsRowLeadingIcon) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        when (icon) {
            is SettingsRowLeadingIcon.Vector -> Icon(
                imageVector = icon.imageVector,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )

            is SettingsRowLeadingIcon.PainterResource -> Icon(
                painter = painterResource(id = icon.drawableResId),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun SettingsMessageItem(
    text: String,
    horizontalPadding: Dp,
    verticalPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
    )
}
