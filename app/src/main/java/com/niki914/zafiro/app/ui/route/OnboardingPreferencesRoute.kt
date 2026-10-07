package com.niki914.zafiro.app.ui.route

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.niki914.uikit.infra.nav.pageViewModel
import com.niki914.zafiro.app.ui.content.OnboardingPreferencesContent
import com.niki914.zafiro.app.ui.model.OnboardingPreferencesEffect
import com.niki914.zafiro.app.ui.model.OnboardingPreferencesIntent
import com.niki914.zafiro.app.ui.model.OnboardingPreferencesViewModel
import com.niki914.zafiro.app.ui.nav.HomePage
import com.niki914.zafiro.app.ui.nav.ZafiroPage

@Composable
internal fun OnboardingPreferencesRoute(
    onResetTo: (ZafiroPage) -> Unit,
) {
    val viewModel = pageViewModel<OnboardingPreferencesViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.sendIntent(OnboardingPreferencesIntent.Load)
    }

    LaunchedEffect(viewModel) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is OnboardingPreferencesEffect.ShowToast -> {
                    Toast.makeText(
                        context,
                        context.getString(effect.messageRes),
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                OnboardingPreferencesEffect.NavigateToHome -> onResetTo(HomePage)
            }
        }
    }

    OnboardingPreferencesContent(
        uiState = uiState,
        onIntent = viewModel::sendIntent,
    )
}
