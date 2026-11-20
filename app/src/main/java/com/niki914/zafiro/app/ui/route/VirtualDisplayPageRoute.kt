package com.niki914.zafiro.app.ui.route

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.uikit.screen.zafiroScreenTopPadding
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
        compact = false,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = topPadding,
            bottom = navBottom + 20.dp,
        ),
        headerContent = {
            CreateBlankVirtualDisplayCard(
                isCreating = uiState.isCreating,
                onClick = {
                    viewModel.sendIntent(VirtualDisplayIntent.CreateBlank(page.conversationId))
                },
            )
        },
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

@Composable
private fun CreateBlankVirtualDisplayCard(
    isCreating: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = remember { G2CardShape(18.dp) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.primaryContainer)
            .clickable(enabled = !isCreating, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(G2CardShape(12.dp))
                .background(colors.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (isCreating) {
                ZafiroLoadingIndicator(
                    color = colors.primary,
                    size = 20.dp,
                    petalSize = 20.dp,
                    strokeWidth = 2.2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AddToQueue,
                    contentDescription = null,
                    tint = colors.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.ui_virtual_display_create_blank_title),
                style = MaterialTheme.typography.titleSmall,
                color = colors.onPrimaryContainer,
            )
            Text(
                text = stringResource(R.string.ui_virtual_display_create_blank_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}
