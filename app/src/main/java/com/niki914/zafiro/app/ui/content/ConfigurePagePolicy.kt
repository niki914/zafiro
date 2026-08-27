package com.niki914.zafiro.app.ui.content

import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ProviderSpec

data class ConfigurePagePolicy(
    val showEndpointSection: Boolean,
    val showEndpointOverrideToggle: Boolean,
    val endpointEditable: Boolean,
)

internal fun onboardingConfigurePolicy(providerSpec: ProviderSpec): ConfigurePagePolicy {
    return ConfigurePagePolicy(
        showEndpointSection = providerSpec.showEndpointConfigInOnboarding,
        showEndpointOverrideToggle = providerSpec.showEndpointConfigInOnboarding,
        endpointEditable = providerSpec.showEndpointConfigInOnboarding,
    )
}

internal fun configurePagePolicy(
    scene: ConfigureScene,
    providerSpec: ProviderSpec,
): ConfigurePagePolicy {
    return when (scene) {
        ConfigureScene.Onboarding -> onboardingConfigurePolicy(providerSpec)

        ConfigureScene.SettingsNew,
        ConfigureScene.SettingsEdit,
        -> ConfigurePagePolicy(
            showEndpointSection = true,
            // 设置页编辑已有配置：端点行始终展示（可覆盖）；新建时给官方端点 + 覆盖开关
            showEndpointOverrideToggle = scene == ConfigureScene.SettingsNew,
            endpointEditable = true,
        )
    }
}
