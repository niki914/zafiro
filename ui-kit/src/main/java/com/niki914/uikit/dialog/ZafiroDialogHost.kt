package com.niki914.uikit.dialog

import androidx.compose.runtime.ProvidableCompositionLocal
import com.niki914.uikit.infra.LiquidDialogHostState
import com.niki914.uikit.infra.LocalHasActiveDialog as InfraLocalHasActiveDialog
import com.niki914.uikit.infra.LocalLiquidDialogHostState

internal typealias ZafiroDialogHostState = LiquidDialogHostState
internal val LocalZafiroDialogHostState = LocalLiquidDialogHostState

val LocalHasActiveDialog: ProvidableCompositionLocal<Boolean> =
    InfraLocalHasActiveDialog
