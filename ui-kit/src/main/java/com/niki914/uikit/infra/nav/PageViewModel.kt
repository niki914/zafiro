package com.niki914.uikit.infra.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

val LocalNavigationEntry = staticCompositionLocalOf<NavigationEntry<out Page>> {
    error("No NavigationEntry provided")
}

@Composable
inline fun <reified VM : ViewModel> pageViewModel(
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
): VM {
    val entry = LocalNavigationEntry.current
    val viewModelClass = VM::class.java
    val defaultKey = viewModelClass.name
    val finalKey = remember(defaultKey, key) {
        if (key.isNullOrBlank()) defaultKey else "$defaultKey:$key"
    }
    val actualFactory = remember(factory) {
        factory ?: ViewModelProvider.NewInstanceFactory()
    }

    return remember(entry, finalKey, actualFactory) {
        ViewModelProvider(entry, actualFactory)[finalKey, viewModelClass]
    }
}

@Composable
inline fun <reified VM : ViewModel> parentPageViewModel(
    key: String? = null,
    factory: ViewModelProvider.Factory? = null,
    noinline predicate: (Page) -> Boolean,
): VM? {
    val controller = LocalNavigationController.current
    val parentEntry = controller.stack.lastOrNull { predicate(it.page) } ?: return null
    val viewModelClass = VM::class.java
    val defaultKey = viewModelClass.name
    val finalKey = remember(defaultKey, key) {
        if (key.isNullOrBlank()) defaultKey else "$defaultKey:$key"
    }
    val actualFactory = remember(factory) {
        factory ?: ViewModelProvider.NewInstanceFactory()
    }

    return remember(parentEntry, finalKey, actualFactory) {
        ViewModelProvider(parentEntry, actualFactory)[finalKey, viewModelClass]
    }
}
