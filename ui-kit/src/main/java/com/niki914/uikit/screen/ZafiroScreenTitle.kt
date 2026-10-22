package com.niki914.uikit.screen

import androidx.compose.runtime.Composable
import com.niki914.uikit.infra.LiquidScreenTitle
import com.niki914.uikit.infra.TitleDirection as InfraTitleDirection

typealias TitleDirection = InfraTitleDirection
typealias ZafiroTitleDirection = InfraTitleDirection
typealias ZafiroScreenTitle = LiquidScreenTitle

fun ZafiroScreenTitleText(value: String, key: Any? = null): ZafiroScreenTitle =
    LiquidScreenTitle.Text(value, key)

fun ZafiroScreenTitleContent(content: @Composable () -> Unit, key: Any? = null): ZafiroScreenTitle =
    LiquidScreenTitle.Content(content, key)
