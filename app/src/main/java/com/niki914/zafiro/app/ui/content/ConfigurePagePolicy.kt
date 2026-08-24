package com.niki914.zafiro.app.ui.content

import com.niki914.zafiro.app.ui.model.ConfigureScene
import com.niki914.zafiro.app.ui.model.ProviderSpec

data class ConfigurePagePolicy(
    val showEndpointSection: Boolean,
    val showEndpointOverrideToggle: Boolean,
    val endpointEditable: Boolean,
    val showAdvancedSection: Boolean,
)

internal fun onboardingConfigurePolicy(providerSpec: ProviderSpec): ConfigurePagePolicy {
    return ConfigurePagePolicy(
        showEndpointSection = providerSpec.showEndpointConfigInOnboarding,
        showEndpointOverrideToggle = providerSpec.showEndpointConfigInOnboarding,
        endpointEditable = providerSpec.showEndpointConfigInOnboarding,
        showAdvancedSection = false,
    )
}

internal fun configurePagePolicy(
    scene: ConfigureScene,
    providerSpec: ProviderSpec,
): ConfigurePagePolicy {
    return when (scene) {
        ConfigureScene.Onboarding,
        ConfigureScene.SettingsProviderSwitch -> onboardingConfigurePolicy(providerSpec)

        ConfigureScene.Settings -> ConfigurePagePolicy(
            showEndpointSection = true,
            showEndpointOverrideToggle = false,
            endpointEditable = true,
            showAdvancedSection = true,
        )
    }
}
