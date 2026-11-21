package com.niki914.zafiro.app.ui.home.composer.suggestion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.drawBackdrop
import com.niki914.uikit.base.LocalAppDarkTheme
import com.niki914.uikit.base.LocalThemeStyle
import com.niki914.uikit.base.ThemeStyle
import com.niki914.uikit.infra.LiquidGlassPresets
import com.niki914.uikit.infra.LocalScreenBackdrop
import com.niki914.uikit.infra.shape.G2FieldShape
import com.niki914.uikit.menu.ZafiroMenuItem

@Composable
fun BoxScope.HomeComposerSuggestionOverlay(
    visible: Boolean,
    suggestions: List<SlashSuggestion>,
    onSelectSuggestion: (SlashSuggestion) -> Unit,
    composerBottomPadding: Dp,
    composerHeight: Dp,
    hasAttachments: Boolean,
    isComposerExpanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val attachmentClearance = if (hasAttachments) 68.dp else 0.dp
    val suggestionBottomPadding = composerBottomPadding + composerHeight + attachmentClearance + 8.dp
    val panelAlignment = if (isComposerExpanded) Alignment.BottomStart else Alignment.BottomCenter
    val panelStartPadding = if (isComposerExpanded) 28.dp else 0.dp

    ComposerSuggestionPanel(
        visible = visible,
        suggestions = suggestions,
        onSelectSuggestion = onSelectSuggestion,
        modifier = modifier
            .align(panelAlignment)
            .padding(
                start = panelStartPadding,
                bottom = suggestionBottomPadding,
            ),
    )
}

@Composable
fun ComposerSuggestionPanel(
    visible: Boolean,
    suggestions: List<SlashSuggestion>,
    onSelectSuggestion: (SlashSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    var lastNonEmptySuggestions by remember { mutableStateOf(suggestions) }
    SideEffect {
        if (suggestions.isNotEmpty()) {
            lastNonEmptySuggestions = suggestions
        }
    }
    val displaySuggestions = if (visible && suggestions.isNotEmpty()) {
        suggestions
    } else {
        lastNonEmptySuggestions
    }

    AnimatedVisibility(
        visible = visible && suggestions.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(150)) + slideInVertically(animationSpec = tween(180)) { it / 3 },
        exit = fadeOut(animationSpec = tween(120)) + slideOutVertically(animationSpec = tween(150)) { it / 3 },
        modifier = modifier,
    ) {
        val themeStyle = LocalThemeStyle.current
        val isDarkTheme = LocalAppDarkTheme.current
        val colorScheme = MaterialTheme.colorScheme
        val panelShape = G2FieldShape(20.dp)

        when (themeStyle) {
            ThemeStyle.LiquidGlass -> {
                val glassStyle = LiquidGlassPresets.Popover
                val resolvedBackdrop = LocalScreenBackdrop.current ?: remember { emptyBackdrop() }
                val surfaceColor = glassStyle.surfaceColor(isDarkTheme, colorScheme)

                Box(
                    modifier = Modifier
                        .width(240.dp)
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        )
                        .drawBackdrop(
                            backdrop = resolvedBackdrop,
                            shape = { panelShape },
                            highlight = { glassStyle.highlight(isDarkTheme) },
                            shadow = null,
                            effects = { glassStyle.applyEffects(this, isDarkTheme) },
                            onDrawSurface = { drawRect(surfaceColor) },
                        )
                        .clip(panelShape),
                ) {
                    SuggestionList(
                        suggestions = displaySuggestions,
                        onSelectSuggestion = onSelectSuggestion,
                    )
                }
            }

            ThemeStyle.Material3,
            ThemeStyle.Material3Expressive -> {
                Surface(
                    modifier = Modifier
                        .width(240.dp)
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    shape = panelShape,
                    color = colorScheme.surfaceContainerHigh,
                ) {
                    SuggestionList(
                        suggestions = displaySuggestions,
                        onSelectSuggestion = onSelectSuggestion,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionList(
    suggestions: List<SlashSuggestion>,
    onSelectSuggestion: (SlashSuggestion) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .width(240.dp)
            .heightIn(max = 240.dp)
            .padding(vertical = 4.dp),
    ) {
        items(
            items = suggestions,
            key = { "${it.category.prefix}:${it.id}" },
        ) { suggestion ->
            ZafiroMenuItem(
                title = suggestion.displayText,
                onClick = { onSelectSuggestion(suggestion) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}
