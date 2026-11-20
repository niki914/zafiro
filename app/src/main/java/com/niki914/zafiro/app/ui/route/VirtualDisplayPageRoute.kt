package com.niki914.zafiro.app.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.screen.ReportTitleBarCollapsed
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.screen.VirtualDisplayAppPicker
import com.niki914.zafiro.app.ui.helper.ZafiroToast
import com.niki914.zafiro.app.ui.model.VirtualDisplayEffect
import com.niki914.zafiro.app.ui.model.VirtualDisplayIntent
import com.niki914.zafiro.app.ui.model.VirtualDisplayViewModel
import com.niki914.zafiro.app.ui.nav.VirtualDisplayPage

@Composable
internal fun VirtualDisplayPageRoute(
    page: VirtualDisplayPage,
    onBack: () -> Unit,
) {
    val viewModel = pageViewModel<VirtualDisplayViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val gridState = rememberLazyGridState()
    val overscrollController = rememberZafiroOverscrollController()

    ReportTitleBarCollapsed {
        gridState.firstVisibleItemIndex > 0 ||
            gridState.firstVisibleItemScrollOffset > 0 ||
            overscrollController.overscrollOffset.value < 0f
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                VirtualDisplayEffect.CreatedAndClose -> onBack()
                is VirtualDisplayEffect.Failed -> {
                    ZafiroToast.show(R.string.ui_virtual_display_create_failed)
                }
            }
        }
    }

    val topPadding = zafiroScreenTopPadding(16.dp)
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    VirtualDisplayAppPicker(
        recentApps = uiState.recentApps,
        allApps = uiState.allApps,
        isLoading = uiState.isLoadingApps,
        enabled = !uiState.isCreating,
        loadingPackageName = uiState.creatingPackageName,
        gridState = gridState,
        overscrollController = overscrollController,
        compact = false,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topPadding,
            bottom = navBottom + 20.dp,
        ),
        onSelectApp = { packageName ->
            viewModel.sendIntent(
                VirtualDisplayIntent.CreateAndLaunch(
                    conversationId = page.conversationId,
                    packageName = packageName,
                )
            )
        },
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
}
