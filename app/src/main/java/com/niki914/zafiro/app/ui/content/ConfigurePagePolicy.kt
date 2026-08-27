package com.niki914.zafiro.app.ui.content

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
