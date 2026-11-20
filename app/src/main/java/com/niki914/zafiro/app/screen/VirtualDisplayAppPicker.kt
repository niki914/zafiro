package com.niki914.zafiro.app.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.niki914.uikit.control.ZafiroLoadingIndicator
import com.niki914.uikit.infra.shape.G2CardShape
import com.niki914.uikit.scroll.ZafiroOverscrollContainer
import com.niki914.uikit.scroll.ZafiroOverscrollController
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.zafiro.api.util.runCatchingCancellable
import com.niki914.zafiro.app.R
import com.niki914.zafiro.chat.agentic.device.AppInfoProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

internal data class VirtualDisplayAppItem(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
)

/**
 * 应用图标异步加载状态：显式区分加载中与无图标兜底。
 */
internal sealed interface AppIconState {
    data object Loading : AppIconState
    data class Ready(val bitmap: ImageBitmap) : AppIconState
    data object Empty : AppIconState
}

internal suspend fun loadVirtualDisplayAppItems(): List<VirtualDisplayAppItem> {
    return runCatchingCancellable {
        AppInfoProvider.cache().listAll().map { info ->
            VirtualDisplayAppItem(
                packageName = info.packageName,
                appName = info.appName,
                isSystemApp = info.isSystemApp,
            )
        }
    }.getOrDefault(emptyList())
}

internal fun resolveRecentAppItems(
    recentPackages: List<String>,
    allApps: List<VirtualDisplayAppItem>,
): List<VirtualDisplayAppItem> {
    if (recentPackages.isEmpty() || allApps.isEmpty()) return emptyList()
    val byPackage = allApps.associateBy(VirtualDisplayAppItem::packageName)
    return recentPackages.mapNotNull { byPackage[it] }
}

private object AppIconMemoryCache {
    private const val MAX_CACHE_ENTRIES = 200
    private const val MAX_ICON_SIZE_PX = 128

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loadDispatcher = Dispatchers.IO.limitedParallelism(4)
    private val icons = LruCache<String, ImageBitmap>(MAX_CACHE_ENTRIES)

    fun get(packageName: String): ImageBitmap? = icons.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        icons.get(packageName)?.let { return it }
        val loaded = withContext(loadDispatcher) {
            icons.get(packageName)?.let { return@withContext it }
            runCatchingCancellable {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                    val src = drawable.bitmap
                    if (src.width > MAX_ICON_SIZE_PX || src.height > MAX_ICON_SIZE_PX) {
                        val scale = MAX_ICON_SIZE_PX.toFloat() / maxOf(src.width, src.height)
                        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
                        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
                        src.scale(targetW, targetH)
                    } else {
                        src
                    }
                } else {
                    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
                    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
                    val targetW = width.coerceAtMost(MAX_ICON_SIZE_PX)
                    val targetH = height.coerceAtMost(MAX_ICON_SIZE_PX)
                    val bmp = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, targetW, targetH)
                    drawable.draw(canvas)
                    bmp
                }
                bitmap.asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            icons.put(packageName, loaded)
        }
        return loaded
    }
}

@Composable
internal fun rememberAppIconState(packageName: String?): AppIconState {
    val context = LocalContext.current
    var state by remember(packageName) {
        mutableStateOf(
            when {
                packageName.isNullOrBlank() -> AppIconState.Empty
                else -> AppIconMemoryCache.get(packageName)?.let(AppIconState::Ready)
                    ?: AppIconState.Loading
            }
        )
    }
    LaunchedEffect(packageName) {
        if (packageName.isNullOrBlank()) {
            state = AppIconState.Empty
            return@LaunchedEffect
        }
        val cached = AppIconMemoryCache.get(packageName)
        if (cached != null) {
            state = AppIconState.Ready(cached)
            return@LaunchedEffect
        }
        state = AppIconState.Loading
        val loaded = AppIconMemoryCache.load(context, packageName)
        state = if (loaded != null) AppIconState.Ready(loaded) else AppIconState.Empty
    }
    return state
}

@Composable
internal fun AppIconBadge(
    packageName: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
) {
    val iconState = rememberAppIconState(packageName)
    val shape = remember(cornerRadius) { G2CardShape(cornerRadius) }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        when (iconState) {
            AppIconState.Loading -> Unit

            is AppIconState.Ready -> {
                Image(
                    bitmap = iconState.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AppIconState.Empty -> {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.52f),
                )
            }
        }
    }
}

private const val PICKER_GRID_COLUMNS = 4

/**
 * 同构 4 列迷你桌面选应用视图：供虚拟屏二级页与悬浮窗展开态选择器共用。
 * 存在最近使用时依次展示最近使用横向栏（最多 20 个）与全部应用网格；无最近使用时直接展示全部应用。
 */
@Composable
internal fun VirtualDisplayAppPicker(
    recentApps: List<VirtualDisplayAppItem>,
    allApps: List<VirtualDisplayAppItem>,
    isLoading: Boolean,
    onSelectApp: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    enabled: Boolean = true,
    loadingPackageName: String? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    overscrollController: ZafiroOverscrollController = rememberZafiroOverscrollController(),
    contentPadding: PaddingValues = if (compact) {
        PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    } else {
        PaddingValues(horizontal = 20.dp, vertical = 12.dp)
    },
) {
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography

    if (isLoading && allApps.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            ZafiroLoadingIndicator(
                color = colors.primary,
                size = if (compact) 24.dp else 28.dp,
                petalSize = 28.dp,
                strokeWidth = 2.4.dp,
            )
        }
        return
    }

    ZafiroOverscrollContainer(
        overscrollController = overscrollController,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(PICKER_GRID_COLUMNS),
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 16.dp),
        ) {
            if (recentApps.isNotEmpty()) {
                item(
                    key = "vd_recent_section_title",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "section_title",
                ) {
                    Text(
                        text = stringResource(R.string.ui_virtual_display_recent_apps),
                        style = if (compact) typography.labelMedium else typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                item(
                    key = "vd_recent_row",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "recent_row",
                ) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp),
                    ) {
                        items(
                            items = recentApps,
                            key = { "recent_${it.packageName}" },
                            contentType = { "app_tile" },
                        ) { app ->
                            AppDesktopTile(
                                app = app,
                                compact = compact,
                                enabled = enabled,
                                isLoading = loadingPackageName == app.packageName,
                                dimmed = loadingPackageName != null && loadingPackageName != app.packageName,
                                onClick = { onSelectApp(app.packageName) },
                                modifier = Modifier.width(if (compact) 58.dp else 72.dp),
                            )
                        }
                    }
                }
                item(
                    key = "vd_all_section_title",
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = "section_title",
                ) {
                    Text(
                        text = stringResource(R.string.ui_virtual_display_all_apps),
                        style = if (compact) typography.labelMedium else typography.labelLarge,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = 4.dp,
                            end = 4.dp,
                            top = 4.dp,
                        ),
                    )
                }
            }

            items(
                items = allApps,
                key = { it.packageName },
                contentType = { "app_tile" },
            ) { app ->
                AppDesktopTile(
                    app = app,
                    compact = compact,
                    enabled = enabled,
                    isLoading = loadingPackageName == app.packageName,
                    dimmed = loadingPackageName != null && loadingPackageName != app.packageName,
                    onClick = { onSelectApp(app.packageName) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AppDesktopTile(
    app: VirtualDisplayAppItem,
    compact: Boolean,
    enabled: Boolean,
    isLoading: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconSize = if (compact) 44.dp else 54.dp
    val cornerRadius = if (compact) 12.dp else 15.dp
    val iconShape = remember(cornerRadius) { G2CardShape(cornerRadius) }
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current

    Column(
        modifier = modifier
            .alpha(if (dimmed) 0.45f else 1f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                    onClick()
                },
            )
            .padding(vertical = if (compact) 4.dp else 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .size(iconSize)
                    .clip(iconShape)
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val indicatorSize = (iconSize * 0.48f).coerceIn(18.dp, 26.dp)
                ZafiroLoadingIndicator(
                    color = colors.primary,
                    size = indicatorSize,
                    petalSize = indicatorSize,
                    strokeWidth = 2.2.dp,
                )
            }
        } else {
            AppIconBadge(
                packageName = app.packageName,
                size = iconSize,
                cornerRadius = cornerRadius,
            )
        }
        Spacer(modifier = Modifier.height(if (compact) 5.dp else 7.dp))
        Text(
            text = app.appName,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
