package com.niki914.nexus.agentic.app.ui.infra.nav

import androidx.compose.runtime.Stable
import com.niki914.nexus.agentic.app.ui.infra.TitleDirection

@Stable
class Navigator<P : Page> internal constructor(
    private val controller: NavigationController<P>,
) {
    fun push(
        page: P,
        direction: TitleDirection = TitleDirection.Forward,
    ) {
        controller.push(page, direction)
    }

    fun pop(
        direction: TitleDirection = TitleDirection.Back,
    ): Boolean {
        return controller.pop(direction)
    }

    fun popMultiple(
        count: Int,
        direction: TitleDirection = TitleDirection.Back,
    ): Int {
        return controller.popMultiple(count, direction)
    }

    fun resetTo(page: P) {
        controller.resetTo(page)
    }
}
