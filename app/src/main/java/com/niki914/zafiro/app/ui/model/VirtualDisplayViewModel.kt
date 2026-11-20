package com.niki914.zafiro.app.ui.model

import androidx.lifecycle.viewModelScope
import com.niki914.logging.Logger
import com.niki914.uikit.base.ComposeMVIViewModel
import com.niki914.zafiro.api.screen.OpenKind
import com.niki914.zafiro.api.screen.OpenResult
import com.niki914.zafiro.api.screen.Screens
import com.niki914.zafiro.app.screen.RecentVirtualDisplayAppsStore
import com.niki914.zafiro.app.screen.VirtualDisplayAppItem
import com.niki914.zafiro.app.screen.loadVirtualDisplayAppItems
import com.niki914.zafiro.app.screen.resolveRecentAppItems
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.launch

internal data class VirtualDisplayUiState(
    val isLoadingApps: Boolean = true,
    val creatingPackageName: String? = null,
    val recentApps: List<VirtualDisplayAppItem> = emptyList(),
    val allApps: List<VirtualDisplayAppItem> = emptyList(),
) {
    val isCreating: Boolean get() = creatingPackageName != null
}

internal sealed interface VirtualDisplayIntent {
    data class CreateAndLaunch(
        val conversationId: String,
        val packageName: String,
    ) : VirtualDisplayIntent
}

internal sealed interface VirtualDisplayEffect {
    data object CreatedAndClose : VirtualDisplayEffect
    data class Failed(val code: String) : VirtualDisplayEffect
}

internal class VirtualDisplayViewModel :
    ComposeMVIViewModel<VirtualDisplayIntent, VirtualDisplayUiState, VirtualDisplayEffect>() {

    init {
        viewModelScope.launch {
            RecentVirtualDisplayAppsStore.ensureLoaded()
            val allApps = loadVirtualDisplayAppItems()
            val recent = resolveRecentAppItems(
                RecentVirtualDisplayAppsStore.recentPackages.value,
                allApps,
            )
            updateState {
                copy(
                    isLoadingApps = false,
                    allApps = allApps,
                    recentApps = recent,
                )
            }
        }
        viewModelScope.launch {
            RecentVirtualDisplayAppsStore.recentPackages.collect { recentPackages ->
                val currentAll = currentState.allApps
                if (currentAll.isNotEmpty()) {
                    updateState {
                        copy(recentApps = resolveRecentAppItems(recentPackages, currentAll))
                    }
                }
            }
        }
    }

    override fun initUiState(): VirtualDisplayUiState = VirtualDisplayUiState()

    override suspend fun handleIntent(intent: VirtualDisplayIntent) {
        when (intent) {
            is VirtualDisplayIntent.CreateAndLaunch -> createAndLaunch(
                conversationId = intent.conversationId,
                packageName = intent.packageName,
            )
        }
    }

    private suspend fun createAndLaunch(
        conversationId: String,
        packageName: String,
    ) {
        if (currentState.isCreating) return
        val screens = findService<Screens>()
        if (screens == null) {
            sendEffect(VirtualDisplayEffect.Failed("SERVICE_UNAVAILABLE"))
            return
        }
        updateState { copy(creatingPackageName = packageName) }
        try {
            // 手动触发虚拟屏创建默认视为已授权知情同意
            XRepo.setPhoneUseConsentGranted(true)
            when (val result = screens.open(OpenKind.VirtualDisplay, ownerConversationId = conversationId)) {
                is OpenResult.Opened -> {
                    screens.launchApp(result.info.id, packageName)
                    sendEffect(VirtualDisplayEffect.CreatedAndClose)
                }

                is OpenResult.Rejected -> {
                    Logger.w(LOG_TAG, "open virtual display rejected code=${result.code}")
                    sendEffect(VirtualDisplayEffect.Failed(result.code))
                }

                is OpenResult.Unsupported -> {
                    sendEffect(VirtualDisplayEffect.Failed("UNSUPPORTED"))
                }
            }
        } finally {
            updateState { copy(creatingPackageName = null) }
        }
    }

    private companion object {
        const val LOG_TAG = "niki914_zafiro_VirtualDisplayVM"
    }
}
