package com.niki914.zafiro.app.ui.viewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.niki914.uikit.screen.ReportTitleBarCollapsed
import com.niki914.uikit.screen.zafiroScreenBottomPadding
import com.niki914.uikit.screen.zafiroScreenTopPadding
import com.niki914.uikit.scroll.ZafiroScrollableContainer
import com.niki914.uikit.scroll.rememberZafiroOverscrollController
import com.niki914.uikit.scroll.rememberZafiroScrollStateAdapter
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.PageBackHandler
import com.niki914.zafiro.app.ui.PageChromeContribution
import com.niki914.zafiro.app.ui.PageChromeMenuItem
import com.niki914.zafiro.app.ui.RegisterPageChrome
import com.niki914.zafiro.app.ui.chat.helper.honestCopyToClipboard
import com.niki914.zafiro.app.ui.helper.PathBitmapState
import com.niki914.zafiro.app.ui.helper.rememberPathBitmapState
import com.niki914.zafiro.app.ui.nav.NoTitle
import com.niki914.zafiro.app.ui.nav.TextTitle
import com.niki914.zafiro.app.ui.nav.TopBarActionSpec
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ViewerPageContent(
    content: ViewerContent,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestOnBack by rememberUpdatedState(onBack)
    val handleBack: () -> Unit = remember { { latestOnBack() } }

    when (content) {
        is ViewerContent.Text -> {
            TextViewerContent(
                text = content.text,
                onBack = handleBack,
                modifier = modifier,
            )
        }

        is ViewerContent.Image -> {
            ImageViewerContent(
                content = content,
                onBack = handleBack,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TextViewerContent(
    text: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboard.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val copyAllLabel = stringResource(R.string.ui_viewer_copy_all)

    RegisterPageChrome(
        remember(copyAllLabel, text, onBack) {
            PageChromeContribution(
                titleSpec = NoTitle,
                leftAction = TopBarActionSpec(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    contentDescription = "Back Button",
                ),
                backHandler = PageBackHandler(
                    shouldConsumeBack = { true },
                    onConsumeBack = onBack,
                ),
                menuItems = listOf(
                    PageChromeMenuItem(
                        key = "copy_all",
                        title = copyAllLabel,
                        onClick = {
                            scope.launch {
                                honestCopyToClipboard(
                                    text = text,
                                    clipboard = clipboard,
                                    hapticFeedback = haptic,
                                )
                            }
                        },
                    )
                ),
            )
        }
    )

    val scrollState = rememberScrollState()
    val overscrollController = rememberZafiroOverscrollController()
    val scrollbarState = rememberZafiroScrollStateAdapter(scrollState, overscrollController)

    ReportTitleBarCollapsed {
        scrollState.value > 0 || overscrollController.overscrollOffset.value < 0f
    }

    Box(modifier = modifier.fillMaxSize()) {
        ZafiroScrollableContainer(
            state = scrollbarState,
            overscrollController = overscrollController,
            topClearance = zafiroScreenTopPadding(20.dp),
            bottomClearance = zafiroScreenBottomPadding(20.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(
                        top = zafiroScreenTopPadding(20.dp),
                        bottom = zafiroScreenBottomPadding(20.dp),
                    )
                    .padding(horizontal = 20.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageViewerContent(
    content: ViewerContent.Image,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val images = content.images.ifEmpty { listOf(content.path) }
    val isMultiImage = images.size > 1
    var isBarBackgroundVisible by rememberSaveable { mutableStateOf(false) }

    ReportTitleBarCollapsed { isBarBackgroundVisible }

    if (isMultiImage) {
        val initialPage = content.initialIndex.coerceIn(0, images.lastIndex)
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { images.size })
        val currentPage = pagerState.currentPage
        val totalPages = images.size

        RegisterPageChrome(
            remember(currentPage, totalPages, onBack) {
                PageChromeContribution(
                    titleSpec = TextTitle("${currentPage + 1} / $totalPages"),
                    titleKey = currentPage,
                    actionsVisible = true,
                    leftAction = TopBarActionSpec(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onBack,
                        contentDescription = "Back Button",
                    ),
                    backHandler = PageBackHandler(
                        shouldConsumeBack = { true },
                        onConsumeBack = onBack,
                    ),
                )
            }
        )

        var isCurrentPageZoomed by remember { mutableStateOf(false) }

        LaunchedEffect(pagerState.currentPage) {
            isCurrentPageZoomed = false
        }

        HorizontalPager(
            state = pagerState,
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            userScrollEnabled = !isCurrentPageZoomed,
        ) { pageIndex ->
            SingleImageViewer(
                path = images[pageIndex],
                modifier = Modifier.fillMaxSize(),
                onZoomStateChanged = { isZoomed ->
                    if (pagerState.currentPage == pageIndex) {
                        isCurrentPageZoomed = isZoomed
                    }
                },
                onTap = { isBarBackgroundVisible = !isBarBackgroundVisible },
            )
        }
    } else {
        RegisterPageChrome(
            remember(onBack) {
                PageChromeContribution(
                    titleSpec = NoTitle,
                    actionsVisible = true,
                    leftAction = TopBarActionSpec(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onBack,
                        contentDescription = "Back Button",
                    ),
                    backHandler = PageBackHandler(
                        shouldConsumeBack = { true },
                        onConsumeBack = onBack,
                    ),
                )
            }
        )

        SingleImageViewer(
            path = images.firstOrNull().orEmpty(),
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            onTap = { isBarBackgroundVisible = !isBarBackgroundVisible },
        )
    }
}

@Composable
private fun SingleImageViewer(
    path: String,
    modifier: Modifier = Modifier,
    onZoomStateChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val state = rememberPathBitmapState(path)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            PathBitmapState.Loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
            }

            is PathBitmapState.Success -> {
                ZoomableImage(
                    bitmap = state.bitmap,
                    modifier = Modifier.fillMaxSize(),
                    onZoomStateChanged = onZoomStateChanged,
                    onTap = onTap,
                )
            }

            PathBitmapState.Error -> {
                ImageUnavailableFallback(
                    path = path,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onTap() })
                        },
                )
            }
        }
    }
}

@Composable
internal fun ImageUnavailableFallback(
    path: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = "Broken Image Icon",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ui_viewer_image_unavailable),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ui_viewer_image_unavailable_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        val fileName = remember(path) { File(path).name }
        if (fileName.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ZoomableImage(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    onZoomStateChanged: (Boolean) -> Unit = {},
    onTap: () -> Unit = {},
) {
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val latestOnTap by rememberUpdatedState(onTap)
    val latestOnZoomStateChanged by rememberUpdatedState(onZoomStateChanged)

    LaunchedEffect(bitmap) {
        snapshotFlow { scale > 1.05f }
            .distinctUntilChanged()
            .collect { latestOnZoomStateChanged(it) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(bitmap) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var isPanStarted = false
                    var totalPan = Offset.Zero
                    val touchSlop = viewConfiguration.touchSlop
                    var wasZoomOrPan = false
                    var canceled = false

                    val containerWidth = size.width.toFloat()
                    val containerHeight = size.height.toFloat()
                    val screenCenter = Offset(containerWidth / 2f, containerHeight / 2f)
                    val bitmapWidth = bitmap.width.toFloat()
                    val bitmapHeight = bitmap.height.toFloat()

                    val fitRatio = if (bitmapWidth > 0f && bitmapHeight > 0f) {
                        minOf(containerWidth / bitmapWidth, containerHeight / bitmapHeight)
                    } else {
                        1f
                    }
                    val displayedWidth = bitmapWidth * fitRatio
                    val displayedHeight = bitmapHeight * fitRatio

                    fun calculateMaxOffset(currentScale: Float): Offset {
                        val maxX = maxOf(0f, (displayedWidth * currentScale - containerWidth) / 2f)
                        val maxY = maxOf(0f, (displayedHeight * currentScale - containerHeight) / 2f)
                        return Offset(maxX, maxY)
                    }

                    do {
                        val event = awaitPointerEvent()
                        canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            val pointerCount = event.changes.count { it.pressed }
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()

                            if (pointerCount > 1 && (zoomChange != 1f || panChange != Offset.Zero)) {
                                wasZoomOrPan = true
                                val newScale = (scale * zoomChange).coerceIn(1f, 5f)
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val focalPoint = if (centroid.isSpecified) {
                                    centroid - screenCenter
                                } else {
                                    Offset.Zero
                                }
                                val rawOffset = offset * zoomChange + focalPoint * (1f - zoomChange) + panChange
                                val maxOffset = calculateMaxOffset(newScale)
                                val newOffset = if (newScale > 1f) {
                                    Offset(
                                        x = rawOffset.x.coerceIn(-maxOffset.x, maxOffset.x),
                                        y = rawOffset.y.coerceIn(-maxOffset.y, maxOffset.y),
                                    )
                                } else {
                                    Offset.Zero
                                }
                                scale = newScale
                                offset = newOffset
                                event.changes.forEach { it.consume() }
                            } else if (scale > 1.05f && pointerCount == 1) {
                                totalPan += panChange
                                if (!isPanStarted && totalPan.getDistance() > touchSlop) {
                                    isPanStarted = true
                                    wasZoomOrPan = true
                                }
                                if (isPanStarted) {
                                    val maxOffset = calculateMaxOffset(scale)
                                    val newOffset = Offset(
                                        x = (offset.x + panChange.x).coerceIn(-maxOffset.x, maxOffset.x),
                                        y = (offset.y + panChange.y).coerceIn(-maxOffset.y, maxOffset.y),
                                    )
                                    offset = newOffset
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                    } while (!canceled && event.changes.any { it.pressed })

                    if (!wasZoomOrPan && !canceled) {
                        latestOnTap()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
