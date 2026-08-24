package com.niki914.uikit.infra.nav

import androidx.compose.runtime.Stable
import com.niki914.uikit.infra.TitleDirection

@Stable
class Navigator<P : Page> internal constructor(
    private val controller: NavigationController<P>,
) {
    fun push(
        page: P,
        direction: com.niki914.uikit.infra.TitleDirection = _root_ide_package_.com.niki914.uikit.infra.TitleDirection.Forward,
    ) {
        controller.push(page, direction)
    }

    fun pop(
        direction: com.niki914.uikit.infra.TitleDirection = _root_ide_package_.com.niki914.uikit.infra.TitleDirection.Back,
    ): Boolean {
        return controller.pop(direction)
    }

    fun popMultiple(
        count: Int,
        direction: com.niki914.uikit.infra.TitleDirection = _root_ide_package_.com.niki914.uikit.infra.TitleDirection.Back,
    ): Int {
        return controller.popMultiple(count, direction)
    }

    fun resetTo(page: P) {
        controller.resetTo(page)
    }
}
