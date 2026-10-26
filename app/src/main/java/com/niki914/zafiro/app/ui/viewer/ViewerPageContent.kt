package com.niki914.zafiro.app.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.screen.ReportTitleBarCollapsed
import com.niki914.uikit.screen.zafiroScreenBottomPadding
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.uikit.scroll.ZafiroScrollableContainer
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.uikit.scroll.rememberZafiroScrollStateAdapter
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.chat.helper.honestCopyToClipboard
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import kotlinx.coroutines.launch

@Composable
fun ViewerPageContent(
    content: ViewerContent,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val copyAllLabel = stringResource(R.string.ui_viewer_copy_all)

    val textToCopy = when (content) {
        is ViewerContent.Text -> content.text
    }

    val latestOnBack by rememberUpdatedState(onBack)

    RegisterPageChrome(
        remember(copyAllLabel, textToCopy) {
            PageChromeContribution(
                leftAction = TopBarActionSpec(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = { latestOnBack() },
                    contentDescription = "Back Button",
                ),
                backHandler = PageBackHandler(
                    shouldConsumeBack = { true },
                    onConsumeBack = { latestOnBack() },
                ),
                menuItems = listOf(
                    PageChromeMenuItem(
                        key = "copy_all",
                        title = copyAllLabel,
                        onClick = {
                            scope.launch {
                                honestCopyToClipboard(
                                    text = textToCopy,
                                    clipboard = clipboard,
                                    hapticFeedback = haptic,
                                )
                            }
                        },
                    )
                ),
            )
        }
    )

    val scrollState = rememberScrollState()
    val overscrollController = rememberZafiroOverscrollController()
    val scrollbarState = rememberZafiroScrollStateAdapter(scrollState, overscrollController)

    ReportTitleBarCollapsed {
        scrollState.value > 0 || overscrollController.overscrollOffset.value < 0f
    }

    Box(modifier = modifier.fillMaxSize()) {
        ZafiroScrollableContainer(
            state = scrollbarState,
            overscrollController = overscrollController,
            topClearance = zafiroScreenTopPadding(20.dp),
            bottomClearance = zafiroScreenBottomPadding(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = zafiroScreenTopPadding(20.dp),
                        bottom = zafiroScreenBottomPadding(20.dp),
                    )
                    .padding(horizontal = 20.dp),
            ) {
                when (content) {
                    is ViewerContent.Text -> {
                        SelectionContainer {
                            Text(
                                text = content.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
