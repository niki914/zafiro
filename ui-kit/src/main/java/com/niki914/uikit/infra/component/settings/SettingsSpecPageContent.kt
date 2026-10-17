package com.niki914.uikit.infra.component.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.component.SettingsGroupCard
import com.niki914.uikit.infra.component.SettingsItemDivider
import com.niki914.uikit.infra.component.SettingsListPageContent
import com.niki914.uikit.infra.component.settings.item.SettingCardItem
import com.niki914.uikit.infra.component.settings.item.SettingExpandableSlot
import com.niki914.uikit.infra.component.settings.item.SettingLeadingSlot
import com.niki914.uikit.infra.component.settings.item.SettingTrailingSlot

/**
 * 统一设置页面 Spec 驱动渲染引擎 (V1 基于新底座)：
 * 1. 外部契约 100% 保持不变，全 App 设置页无感平替；
 * 2. 内部全面由统一设置项底座 [SettingCardItem] 与 [SettingSlots] 驱动；
 * 3. 彻底消除冗余嵌套：CardList 散开模式原生以 isStandaloneCard 渲染，消灭了外包 SettingsGroupCard 的临时 Workaround；
 * 4. 标题防挤压、End-to-End 右对齐、Slider 槽位 20% 防抖和绝对垂直居中全链路生效。
 */
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
                    SettingsRowItem(
                        row = row,
                        isCardList = false,
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
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (!section.title.isNullOrBlank()) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    section.rows.forEach { row ->
                        SettingsRowItem(
                            row = row,
                            isCardList = true,
                            expandedSliderId = expandedSliderId,
                            onExpandedSliderChange = onExpandedSliderChange,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRowItem(
    row: SettingsRowSpec,
    isCardList: Boolean,
    expandedSliderId: String?,
    onExpandedSliderChange: (String?) -> Unit,
    onAction: (SettingsRowAction) -> Unit,
) {
    when (row) {
        is SettingsRowSpec.Navigation -> {
            val leadingSlot = row.leadingIcon?.toSettingLeadingSlot() ?: SettingLeadingSlot.None
            val trailingSlot = if (row.currentState != null) {
                SettingTrailingSlot.Preview(text = row.currentState, showChevron = true)
            } else {
                SettingTrailingSlot.Chevron()
            }
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                leadingSlot = leadingSlot,
                trailingSlot = trailingSlot,
                isStandaloneCard = isCardList,
                enabled = row.enabled,
                onClick = { onAction(SettingsRowAction.Navigate(row.id)) },
            )
        }

        is SettingsRowSpec.Toggle -> {
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                trailingSlot = SettingTrailingSlot.Toggle(checked = row.checked),
                isStandaloneCard = isCardList,
                enabled = row.enabled,
                onClick = { onAction(SettingsRowAction.ToggleChanged(row.id, !row.checked)) },
            )
        }

        is SettingsRowSpec.ToggleNavigation -> {
            require(isCardList) {
                "SettingsRowSpec.ToggleNavigation requires SettingsSectionLayout.CardList."
            }
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                trailingSlot = SettingTrailingSlot.Toggle(
                    checked = row.checked,
                    onCheckedChange = { checked ->
                        onAction(SettingsRowAction.ToggleChanged(row.id, checked))
                    },
                ),
                isStandaloneCard = true,
                enabled = row.enabled,
                onClick = { onAction(SettingsRowAction.Navigate(row.id)) },
            )
        }

        is SettingsRowSpec.Value -> {
            val leadingSlot = row.leadingIcon?.toSettingLeadingSlot() ?: SettingLeadingSlot.None
            val trailingSlot = if (row.currentState != null) {
                SettingTrailingSlot.Preview(text = row.currentState, showChevron = false)
            } else {
                SettingTrailingSlot.None
            }
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                leadingSlot = leadingSlot,
                trailingSlot = trailingSlot,
                isStandaloneCard = isCardList,
                enabled = row.enabled,
                onClick = null,
            )
        }

        is SettingsRowSpec.Action -> {
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                isStandaloneCard = isCardList,
                enabled = row.enabled,
                onClick = { onAction(SettingsRowAction.Click(row.id)) },
            )
        }

        is SettingsRowSpec.Destructive -> {
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                isStandaloneCard = isCardList,
                enabled = row.enabled,
                onClick = { onAction(SettingsRowAction.Click(row.id)) },
            )
        }

        is SettingsRowSpec.Message -> {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = row.horizontalPadding, vertical = row.verticalPadding),
            )
        }

        is SettingsRowSpec.Slider -> {
            val isExpanded = (expandedSliderId == row.id)
            SettingCardItem(
                title = row.title,
                summary = row.summary,
                trailingSlot = SettingTrailingSlot.Preview(
                    text = row.valueLabel ?: "${(row.value * 100).toInt()}%",
                    showChevron = true,
                ),
                isExpanded = isExpanded,
                onExpandedChange = { expanded ->
                    onExpandedSliderChange(if (expanded) row.id else null)
                },
                expandableSlot = SettingExpandableSlot.Slider(
                    value = row.value,
                    valueRange = row.valueRange,
                    steps = row.steps,
                    startLabel = row.startLabel,
                    endLabel = row.endLabel,
                    enabled = row.enabled,
                    onValueChange = { onAction(SettingsRowAction.SliderChanged(row.id, it)) },
                    onValueCommit = { onAction(SettingsRowAction.SliderCommit(row.id, it)) },
                ),
                isStandaloneCard = isCardList,
                enabled = row.enabled,
            )
        }
    }
}

@Composable
private fun SettingsRowLeadingIcon.toSettingLeadingSlot(): SettingLeadingSlot {
    return when (this) {
        is SettingsRowLeadingIcon.Vector -> SettingLeadingSlot.CircularIcon(
            imageVector = imageVector,
        )

        is SettingsRowLeadingIcon.PainterResource -> SettingLeadingSlot.CircularIcon(
            painter = painterResource(id = drawableResId),
        )
    }
}
