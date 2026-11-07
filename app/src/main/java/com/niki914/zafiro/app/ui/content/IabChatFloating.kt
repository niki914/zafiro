package com.niki914.zafiro.app.ui.content

import android.graphics.Point
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.ui.model.IabBrowserIntent
import com.niki914.zafiro.app.ui.model.IabBrowserViewModel
import com.niki914.zafiro.app.ui.model.isLikelyUrl
import com.niki914.zafiro.chat.agentic.iab.IabManager
import com.niki914.zafiro.repo.IabPaneGeometry
import com.niki914.zafiro.repo.IabPaneGeometryCodec
import com.niki914.zafiro.repo.XRepo
import com.niki914.uikit.infra.liquidScreenTopPadding
import com.niki914.uikit.infra.nav.pageViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 聊天界面内的实时 IAB 浮窗（XRepo.showLiveIabInChat 开启时由调用方挂出，r13）：
 *
 * - 默认收起为左上角小气泡（r15 点 3：贴聊天历史图标一侧；与 agent 共享 [IabManager] 同一组标签）；点按气泡展开为完整面板。
 * - 展开面板：标题栏（拖动把手，可整体移动）+ 地址栏（Enter 导航 + 刷新 + 在其它应用打开）
 *   + 标签条（r15 点 4，多标签时可点按切换）+ 进度 + 验证墙（CAPTCHA）横幅 + WebView
 *   + 左下 / 右下两个缩放把手（r15 点 1/2，可调整宽高）。
 *   WebView 随面板尺寸自动 reflow，网页版式保持响应式。
 * - 几何（尺寸 + 位置，相对聊天区 0..1 比例）持久化到 XRepo，重启保留。
 * - 键盘无关（r13 点 4）：面板位置 / 尺寸不随 IME 变化——键盘弹出或收起时它不跳、不变。
 * - agent 控制路径（BrowserBuiltin / verify_check）不经过本文件：本文件只消费
 *   [IabBrowserViewModel]，标签 / 导航 / 轮询 / 探测语义与设置页完全一致（r13 点 3）。
 */
@Composable
fun IabChatFloating(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()

    // 几何恢复（尺寸 + 位置，0..1 相对聊天区；缺省 = 宽 60% / 高 45% / 右上角）。
    var geo by remember {
        mutableStateOf(
            XRepo.iabPaneGeometrySetting.value
                ?.let { IabPaneGeometryCodec.parse(it) }
                ?: IabPaneGeometry(widthRatio = 0.6f, heightRatio = 0.45f, xRatio = 1f, yRatio = 0f),
        )
    }

    // 展开态：进入默认收起为气泡；展开 / 折叠本会话内保持，不写回设置。
    var expanded by remember { mutableStateOf(false) }

    // 聊天区实测尺寸（像素）：0..1 比例 ↔ 像素的换算基准。
    var areaPx by remember { mutableStateOf(Point(0, 0)) }

    // 面板左上角相对聊天区左上的偏移（像素）与面板宽高（像素）——持久化 / 回读基准（Point 形式）。
    // r18 点 9：实时几何不再走普通 Int 参数（那会每帧重组整个 Surface + AndroidView）；
    // 面板内部用 State<IntSize> / State<IntOffset>，只在 layout / offset 的 lambda 里读取，
    // 拖动 / 缩放只重新布局不重新组合，WebView 宿主实例全程存活（IabPaneGestures.kt）。
    var paneOffsetPx by remember { mutableStateOf(Point(0, 0)) }
    var paneSizePx by remember { mutableStateOf(Point(560, 432)) }
    val livePaneOffsetPx = remember { mutableStateOf(IntOffset(0, 0)) }
    val livePaneSizePx = remember { mutableStateOf(IntSize(560, 432)) }
    val paneGesture = remember { IabPaneGestureState() }

    // 聊天区首帧 / 尺寸变化：从持久化比例还原面板几何。
    // 顶边同样避让 Liquid 顶栏（持久化的旧几何可能已压在其下，r14 点 4）。
    val topClearancePx = with(LocalDensity.current) { liquidScreenTopPadding(4.dp).toPx() }.toInt()
    LaunchedEffect(areaPx, geo, paneGesture) {
        if (areaPx.x <= 0 || areaPx.y <= 0) return@LaunchedEffect
        if (paneGesture.isDragging) return@LaunchedEffect
        val restoredSize = IntSize(
            (geo.widthRatio * areaPx.x).toInt().coerceIn(IAB_MIN_PANE_WIDTH_PX, areaPx.x),
            (geo.heightRatio * areaPx.y).toInt().coerceIn(IAB_MIN_PANE_HEIGHT_PX, areaPx.y),
        )
        // xRatio / yRatio 是左上角锚点比：换算回像素偏移。
        val restoredOffset = clampIabMoveOffset(
            offset = IntOffset(
                (geo.xRatio * (areaPx.x - restoredSize.width).coerceAtLeast(0)).toInt(),
                (geo.yRatio * (areaPx.y - restoredSize.height).coerceAtLeast(0)).toInt()
                    .coerceAtLeast(topClearancePx),
            ),
            size = restoredSize,
            area = IntSize(areaPx.x, areaPx.y),
            topClearancePx = topClearancePx,
        )
        paneSizePx = Point(restoredSize.width, restoredSize.height)
        paneOffsetPx = Point(restoredOffset.x, restoredOffset.y)
        livePaneSizePx.value = restoredSize
        livePaneOffsetPx.value = restoredOffset
    }

    // 提交几何：像素 → 0..1 比例写回 XRepo。
    fun persist() {
        if (areaPx.x <= 0 || areaPx.y <= 0) return
        val g = geo.copy(
            widthRatio = (paneSizePx.x.toFloat() / areaPx.x).coerceIn(0.35f, 1f),
            heightRatio = (paneSizePx.y.toFloat() / areaPx.y).coerceIn(0.2f, 1f),
            xRatio = (paneOffsetPx.x.toFloat() / (areaPx.x - paneSizePx.x).coerceAtLeast(1)).coerceIn(0f, 1f),
            yRatio = (paneOffsetPx.y.toFloat() / (areaPx.y - paneSizePx.y).coerceAtLeast(1)).coerceIn(0f, 1f),
        )
        geo = g
        scope.launch {
            runCatching { XRepo.setIabPaneGeometry(IabPaneGeometryCodec.encode(g)) }
        }
    }

    /**
     * 移动回调（r18 点 9）：父层是唯一 clamp 的地方。
     * commitOnEnd = false 只更新 live State（不重组 / 不落盘）；
     * commitOnEnd = true 写回持久化 Point 并落盘 ratio。
     */
    fun handleIabMove(newOffsetPx: IntOffset, commitOnEnd: Boolean) {
        if (areaPx.x <= 0 || areaPx.y <= 0) return
        val clamped = clampIabMoveOffset(
            offset = newOffsetPx,
            size = livePaneSizePx.value,
            area = IntSize(areaPx.x, areaPx.y),
            topClearancePx = topClearancePx,
        )
        livePaneOffsetPx.value = clamped
        if (commitOnEnd) {
            paneOffsetPx = Point(clamped.x, clamped.y)
            persist()
        }
    }

    /**
     * 缩放回调（r18 点 9）：把手只报「期望几何」，父层按锚定边做 clamp
     * （BottomEnd 保持左上角不动；BottomStart 保持右上角不动，左缘 / 顶边钉住）。
     */
    fun handleIabResize(newSizePx: IntSize, newOffsetPx: IntOffset, commitOnEnd: Boolean) {
        if (areaPx.x <= 0 || areaPx.y <= 0) return
        val clamped = clampIabResizeGeometry(
            desiredSize = newSizePx,
            desiredOffset = newOffsetPx,
            previousSize = livePaneSizePx.value,
            previousOffset = livePaneOffsetPx.value,
            area = IntSize(areaPx.x, areaPx.y),
            topClearancePx = topClearancePx,
        )
        livePaneSizePx.value = clamped.size
        livePaneOffsetPx.value = clamped.offset
        if (commitOnEnd) {
            paneSizePx = Point(clamped.size.width, clamped.size.height)
            paneOffsetPx = Point(clamped.offset.x, clamped.offset.y)
            persist()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { areaPx = Point(it.width, it.height) },
    ) {
        if (!expanded) {
            // ---- 收起态：左上角小气泡（点按展开，r15 点 3：贴聊天历史图标一侧）----
            // 顶边距必须避让 Liquid 顶栏（状态栏 + 56.dp 标题栏，由壳层下发），
            // 否则气泡压在通知栏之下不可见、不可触达（r14 点 4）。
            val bubbleTopInset = liquidScreenTopPadding(16.dp)
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = bubbleTopInset, start = 16.dp)
                    .size(52.dp),
            ) {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = stringResource(R.string.ui_iab_expand),
                    )
                }
            }

        } else {
            // ---- 展开态：可拖动 + 可缩放的浮窗面板（屏幕指针式手势，键盘无关，r18 点 9）----
            IabExpandedPane(
                liveSize = livePaneSizePx,
                liveOffset = livePaneOffsetPx,
                gesture = paneGesture,
                onMoveFromGesture = { offset, commit -> handleIabMove(offset, commit) },
                onResizeFromGesture = { size, offset, commit -> handleIabResize(size, offset, commit) },
                onClearArmedClose = {},
                onCollapse = {
                    expanded = false
                    persist()
                },
            )
        }
    }
}

/**
 * 展开面板：标题栏（拖动把手 + 折叠 / 关闭按钮）+ 地址栏（Enter / Go 导航 + 刷新
 * + 在其它应用打开）+ 标签条 + 进度 + 验证墙横幅 + WebView + 左下 / 右下缩放把手（r15 点 1/4）。
 * 实时几何由 [liveSize] / [liveOffset] 两个 State 承载（IabPaneGestures.kt）：
 * 拖动 / 缩放只触发重新布局，不重组本 Surface，WebView 宿主全程存活（r18 点 9）。
 */
@Composable
private fun IabExpandedPane(
    liveSize: State<IntSize>,
    liveOffset: State<IntOffset>,
    gesture: IabPaneGestureState,
    onMoveFromGesture: (IntOffset, Boolean) -> Unit,
    onResizeFromGesture: (IntSize, IntOffset, Boolean) -> Unit,
    onClearArmedClose: () -> Unit,
    onCollapse: () -> Unit,
) {
    val context = LocalContext.current

    // r18 点 9：rememberUpdatedState 锁定最新回调，pointerInput 不再因重组重启。
    val currentOnMove by rememberUpdatedState(onMoveFromGesture)
    val currentOnResize by rememberUpdatedState(onResizeFromGesture)
    val currentOnClearArmedClose by rememberUpdatedState(onClearArmedClose)

    // 把手视觉：drawBehind 内是 DrawScope（非 Composable）：色 / 描边宽在此处预取。
    val handleStrokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val handleLineColor = MaterialTheme.colorScheme.primary
    val handleFill = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)

    val viewModel = pageViewModel<IabBrowserViewModel>()
    val uiState by viewModel.uiStateFlow.collectAsState()
    var urlDraft by remember { mutableStateOf("") }

    // 加载 + 地址栏回填 + 标签轮询 + 验证墙探测（r12 语义原样保留）。
    LaunchedEffect(Unit) {
        viewModel.sendIntent(IabBrowserIntent.Load)
    }
    // r19 BUG3：新标签 URL 空白 → 立即清空草稿；加载中保持现状；加载完成后回填最终 URL。
    LaunchedEffect(uiState.activeTabId, uiState.progress) {
        val active = uiState.tabs.firstOrNull { it.active }
        if (active != null) {
            if (active.url.isBlank()) {
                urlDraft = ""
            } else if (uiState.progress == 100 && urlDraft != active.url) {
                urlDraft = active.url
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(15) {
            delay(1_000)
            viewModel.sendIntent(IabBrowserIntent.RefreshTabs)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3_000)
            if (!uiState.captchaPending) {
                viewModel.sendIntent(IabBrowserIntent.RefreshCaptcha)
            }
        }
    }

    val holder = remember { FrameLayout(context) }

    // r19 BUG1：onGloballyPositioned 捕获标题栏 Row 的 LayoutCoordinates（holder），
    // 供 pointerInput 内的 localToWindow 取 window 绝对坐标（legacy scope 无转换方法；
    // 本 Compose 版本 positionInWindow 仅无参扩展函数，成员方法只有 localToWindow）。
    var moveRowCoordinates: LayoutCoordinates? = null
    DisposableEffect(uiState.activeTabId) {
        val tabId = uiState.activeTabId
        runCatching { IabManager.attachUi(holder, tabId) }
        onDispose {
            runCatching { IabManager.detachUi(holder) }
        }
    }

    // r16：双击关闭标签（armed-close）——先点激活标签 → 武装（红 X + 标题变灰）→
    // 再点该标签 → CloseTab；点别处（Header / URL / 标签条容器）自动解除武装。
    var armedCloseTabId by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier
            .iabPaneSize(liveSize)
            .iabPaneOffset(liveOffset),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // r16 点 4：点标签条以外的 Compose 区域（Header / URL / 进度 / 验证墙）
                // 解除 armed-close 武装。WebView 自身消费 touch 不会走到这里，无需额外处理。
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        armedCloseTabId = null
                        currentOnClearArmedClose()
                    })
                },
        ) {
            // ---- 标题栏：左侧为拖动把手（可整体移动面板），右侧为折叠 / 关闭 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    // r18 点 9：屏幕指针式移动——面板自身在指针底下移动 / 缩放，
                    // 本地坐标会自我反馈漂移；改用窗口绝对坐标几何：
                    // 起点几何 + 起点窗口指针各捕获一次，
                    // 每帧 desired = start + (currentWindowPointer - startWindowPointer)，
                    // 父层 clamp 后写回 live State，本 Composable 不重组。
                    // r19 BUG1（tremor 根因修复，Qwen 指定方案 line 84「remember { } of
                    // the current LayoutCoordinates」）：legacy pointerInput {} 的
                    // PointerInputScope 没有 positionInWindow（编译期已证实），节点 API
                    // 又在本项目 Compose 版本不可用。改用 onGloballyPositioned 把本
                    // Row 的 LayoutCoordinates 写进 holder，再在 pointerInput 里读它调
                    // positionInWindow（window 绝对坐标）；面板移动/缩放时本地坐标
                    // 自我反馈漂移（tremor 根因）随之消失。
                    .onGloballyPositioned { moveRowCoordinates = it }
                    .pointerInput(liveSize, liveOffset, gesture) {
                        detectDragGestures(
                            onDragStart = { startLocal ->
                                // r19 BUG1：Down 时刻的本地坐标→window 绝对坐标，逐帧
                                // window delta 不再受面板自身移动 / 缩放反馈（tremor 根因）。
                                gesture.beginMove(
                                    currentSize = liveSize.value,
                                    currentOffset = liveOffset.value,
                                    downWindow = moveRowCoordinates?.localToWindow(startLocal)
                                        ?: Offset.Zero,
                                )
                            },
                            onDrag = { change, _ ->
                                if (
                                    gesture.isDragging &&
                                    gesture.mode == IabPaneGestureMode.Move
                                ) {
                                    val windowPos = moveRowCoordinates?.localToWindow(change.position)
                                    if (windowPos == null) return@detectDragGestures
                                    val dx = windowPos.x - gesture.startDragWindow.x
                                    val dy = windowPos.y - gesture.startDragWindow.y
                                    if (dx != 0f || dy != 0f) {
                                        currentOnMove(
                                            IntOffset(
                                                (gesture.startOffset.x + dx).toInt(),
                                                (gesture.startOffset.y + dy).toInt(),
                                            ),
                                            false,
                                        )
                                    }
                                    change.consume()
                                }
                            },
                            onDragEnd = {
                                if (gesture.isDragging && gesture.mode == IabPaneGestureMode.Move) {
                                    currentOnMove(liveOffset.value, true)
                                    gesture.end()
                                }
                            },
                            onDragCancel = {
                                if (gesture.isDragging && gesture.mode == IabPaneGestureMode.Move) {
                                    currentOnMove(gesture.startOffset, true)
                                    gesture.end()
                                }
                            },
                        )
                    }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.ui_iab_bubble),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onCollapse, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Compress,
                        contentDescription = stringResource(R.string.ui_iab_collapse),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ---- 地址栏：Enter / Go 导航 + 刷新 + 在其它应用打开（UI 侧 ACTION_VIEW，不进 agent）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // r18 点 11：导航按钮对（与设置页 IabBrowserContent 工具行同款，UI 侧不进 agent）。
                IconButton(
                    onClick = { viewModel.sendIntent(IabBrowserIntent.Back) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.ui_iab_back),
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = { viewModel.sendIntent(IabBrowserIntent.Forward) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.ArrowForward,
                        contentDescription = stringResource(R.string.ui_iab_forward),
                        modifier = Modifier.size(16.dp),
                    )
                }
                OutlinedTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(stringResource(R.string.ui_iab_address_hint)) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Go,
                        keyboardType = KeyboardType.Uri,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val target = urlDraft.trim()
                            if (target.isNotEmpty()) {
                                // r16 点 5：非 URL 输入走 Google 搜索（isLikelyUrl 判定）。
                                val final = if (isLikelyUrl(target)) target
                                    else "https://www.google.com/search?q=${java.net.URLEncoder.encode(target, Charsets.UTF_8)}"
                                viewModel.sendIntent(IabBrowserIntent.Navigate(final))
                            }
                        },
                    ),
                )
                IconButton(
                    onClick = {
                        val target = urlDraft.trim()
                        if (target.isNotEmpty()) {
                            val final = if (isLikelyUrl(target)) target
                                else "https://www.google.com/search?q=${java.net.URLEncoder.encode(target, Charsets.UTF_8)}"
                            viewModel.sendIntent(IabBrowserIntent.Navigate(final))
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.ui_iab_reload),
                        modifier = Modifier.size(16.dp),
                    )
                }
                IconButton(
                    onClick = {
                        val url = urlDraft.trim()
                        if (url.isNotEmpty()) {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        Uri.parse(if (url.contains("://")) url else "https://$url"),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Launch,
                        contentDescription = stringResource(R.string.ui_iab_open_in_app),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            // ---- 标签条（r15 点 4）：紧凑小卡片，点按切标签；与设置页共享 IabManager ----
            // r16 点 4：双击关闭（armed-close）——点激活标签一次 = 武装（红 X + 标题变灰），
            // 再点 = CloseTab；点别处（Column 的 pointerInput 层）解除武装。
            // r17 点 4/7：单标签也显示标签条（标签 pill + 末尾 + 新开标签按钮），
            // 不再在 size > 1 时才出现。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                uiState.tabs.forEach { tab ->
                    val armed = armedCloseTabId == tab.id
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (tab.active) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (tab.active) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable {
                                when {
                                    !tab.active -> {
                                        viewModel.sendIntent(IabBrowserIntent.SwitchTab(tab.id))
                                        armedCloseTabId = null
                                    }
                                    tab.active && !armed -> armedCloseTabId = tab.id
                                    else -> {
                                        viewModel.sendIntent(IabBrowserIntent.CloseTab(tab.id))
                                        armedCloseTabId = null
                                    }
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Box(contentAlignment = Alignment.CenterEnd) {
                            Text(
                                // r17 点 5：无标题标签用本地化「新标签」字符串，不再硬编码中文。
                                text = tab.title.take(10).ifBlank { stringResource(R.string.ui_iab_new_tab) },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (armed) Modifier.alpha(0.4f) else Modifier,
                            )
                            if (armed) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.ui_iab_close_tab),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .align(Alignment.CenterEnd)
                                        .padding(start = 2.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                // r17 点 7：标签条末尾的 + 按钮：新开一个标签（与 agent 的 NewTab 同一意图）。
                IconButton(
                    onClick = {
                        armedCloseTabId = null
                        viewModel.sendIntent(IabBrowserIntent.NewTab)
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.ui_iab_new_tab),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // r16 点 12：平滑进度条替换 LinearProgressIndicator
            IabSmoothProgressBar(
                progress = uiState.progress,
                busy = uiState.busy,
                loadToken = uiState.loadToken,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            )

            if (uiState.captchaPending) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.ui_iab_captcha_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
            }

            // ---- WebView（实例归 IabManager，与 agent 共享）+ 右下 / 左下缩放把手（r18 点 9）----
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(
                    factory = { holder },
                    modifier = Modifier.fillMaxSize(),
                )
                if (uiState.tabs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ui_iab_no_tab),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // 左下 / 右下两个缩放把手（r15 点 1）：对称热区。
                // r18 点 9：改用共享 IabPaneGestureState + 屏幕指针几何（IabResizeHandle）——
                // 两把手不再各持 anchor/delta，拖拽开始时各捕获一次起点几何，
                // 每帧 desired = start + screenDelta，父层按锚定边 clamp，
                // 手势结束 / 取消才写回持久化几何。
                Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                    IabResizeHandle(
                        corner = IabResizeCorner.BottomEnd,
                        liveSize = liveSize,
                        liveOffset = liveOffset,
                        gesture = gesture,
                        onResizeFromGesture = currentOnResize,
                        handleFill = handleFill,
                        handleLineColor = handleLineColor,
                        handleStrokePx = handleStrokePx,
                    )
                }
                Box(modifier = Modifier.align(Alignment.BottomStart)) {
                    IabResizeHandle(
                        corner = IabResizeCorner.BottomStart,
                        liveSize = liveSize,
                        liveOffset = liveOffset,
                        gesture = gesture,
                        onResizeFromGesture = currentOnResize,
                        handleFill = handleFill,
                        handleLineColor = handleLineColor,
                        handleStrokePx = handleStrokePx,
                    )
                }
            }
        }
    }
}
