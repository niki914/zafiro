package com.niki914.uikit.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** 可以单独换皮的界面部分。缺键或非法值时跟随全局 [ThemeStyle]。 */
enum class UiStylePart(val storageKey: String) {
    TopBarPlate("top_bar_plate"),
    TopBarButtons("top_bar_buttons"),
    Button("button"),
    Slider("slider"),
    Switch("switch"),
    PageScroll("page_scroll"),
    Dialog("dialog"),
    Menu("menu"),
    TextField("text_field"),
    Settings("settings"),
    Composer("composer"),
    Search("search"),
    Loading("loading"),
}

/** 没有覆盖项。调用方继续用全局 [ThemeStyle]。实例固定，避免静态 CompositionLocal 整树失效。 */
val FollowGlobalUiStyle = UiStyleLookup { null }

/** 返回该部分的覆盖风格。null 表示跟随 [LocalThemeStyle]。 */
fun interface UiStyleLookup {
    fun styleFor(part: UiStylePart): ThemeStyle?
}

val LocalUiStyleLookup = staticCompositionLocalOf<UiStyleLookup> { UiStyleLookup { null } }

@Composable
fun resolvedUiStyle(part: UiStylePart): ThemeStyle =
    LocalUiStyleLookup.current.styleFor(part) ?: LocalThemeStyle.current
