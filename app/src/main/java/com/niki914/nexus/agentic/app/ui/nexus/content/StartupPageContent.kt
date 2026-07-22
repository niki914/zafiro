package com.niki914.nexus.agentic.app.ui.nexus.content

import android.graphics.Path
import android.graphics.PathMeasure
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.niki914.nexus.agentic.animation.PointerCurveMath
import com.niki914.nexus.agentic.app.R
import com.niki914.nexus.agentic.app.ui.infra.component.MaterialTintLiquidButton
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val LETTERS = "NEXUS"
private val LetterFontSize = 96.sp
private val LetterSpacing = 28.dp

/** Persists across back-navigation so the demo only plays once per process lifetime. */
private var demoHasPlayed = false

@Composable
fun StartupPageContent(
    onDemoComplete: () -> Unit,
) {
    val density = LocalDensity.current
    val interactive = demoHasPlayed
    val scrollState = rememberScrollState()
    val speedLut = remember { PointerCurveMath.buildSpeedLut() }

    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var buttonCenterX by remember { mutableFloatStateOf(0f) }
    var buttonCenterY by remember { mutableFloatStateOf(0f) }

    // Scroll progress: used for demo timing and button show/hide trigger
    val scrollProgress = if (scrollState.maxValue > 0)
        scrollState.value.toFloat() / scrollState.maxValue else 0f

    // Cursor state
    val cursorAlpha = remember { Animatable(0f) }
    val cursorScale = remember { Animatable(1f) }
    var cursorPosX by remember { mutableFloatStateOf(0f) }
    var cursorPosY by remember { mutableFloatStateOf(0f) }
    var cursorHeading by remember { mutableFloatStateOf(PointerCurveMath.IDLE_HEADING_RAD) }
    val buttonScale = remember { Animatable(1f) }

    // ---- Auto-demo animation (first visit only) ----
    if (!interactive) {
        LaunchedEffect(Unit) {
            // Wait for container size — the Column layout depends on it
            snapshotFlow { containerHeightPx }
                .first { it > 0f }

            val h = containerHeightPx
            val w = containerWidthPx
            val centerX = w * 0.5f
            val centerY = h * 0.5f
            val lowerY = h * 0.72f
            val upperY = h * 0.28f
            val pointerEasing = Easing { t -> PointerCurveMath.timeToDistance(t, speedLut) }

            // Helper: bezier curve movement with heading rotation
            suspend fun animateAlongCurve(toX: Float, toY: Float) {
                val path = PointerCurveMath.buildCurve(
                    cursorPosX, cursorPosY, cursorHeading, toX, toY,
                )
                val durMs = PointerCurveMath.curveDurationMs(
                    PathMeasure(path, false).length,
                )
                val durationNs = durMs * 1_000_000L
                val startTime = withFrameNanos { it }
                while (true) {
                    val elapsed = withFrameNanos { it } - startTime
                    val timeFrac = (elapsed.toFloat() / durationNs).coerceIn(0f, 1f)
                    val distFrac = PointerCurveMath.timeToDistance(timeFrac, speedLut)
                    val sample = PointerCurveMath.sampleCurve(path, distFrac)
                    cursorPosX = sample.x
                    cursorPosY = sample.y
                    cursorHeading = sample.headingRad
                    if (timeFrac >= 1f) break
                }
            }

            // Helper: straight-line swipe movement with fixed heading
            suspend fun animateSwipe(toX: Float, toY: Float) {
                val fromX = cursorPosX
                val fromY = cursorPosY
                val path = Path().apply {
                    moveTo(fromX, fromY)
                    lineTo(toX, toY)
                }
                val dx = toX - fromX
                val dy = toY - fromY
                val dist = sqrt(dx * dx + dy * dy)
                val durMs = PointerCurveMath.curveDurationMs(dist)
                val durationNs = durMs * 1_000_000L
                val startTime = withFrameNanos { it }
                cursorHeading = atan2(dy, dx)
                while (true) {
                    val elapsed = withFrameNanos { it } - startTime
                    val timeFrac = (elapsed.toFloat() / durationNs).coerceIn(0f, 1f)
                    val distFrac = PointerCurveMath.timeToDistance(timeFrac, speedLut)
                    val sample = PointerCurveMath.sampleCurve(path, distFrac)
                    cursorPosX = sample.x
                    cursorPosY = sample.y
                    if (timeFrac >= 1f) break
                }
            }

            // Initial position
            cursorPosX = centerX
            cursorPosY = centerY

            // Phase 1: cursor fades in (matches PointerOverlay FADE_DURATION_MS = 300ms)
            delay(500)
            cursorAlpha.animateTo(1f, tween(300))
            delay(300)

            // Phase 2: cursor moves down to lower area (bezier curve)
            animateAlongCurve(centerX, lowerY)
            delay(200)

            // Phase 3: swipe up (straight line, fixed heading), then list scrolls
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            animateSwipe(centerX, upperY)
            scrollState.animateScrollTo(
                scrollState.maxValue,
                tween(1100, easing = pointerEasing),
            )
            delay(200)

            // Ensure button layout coordinates are ready
            snapshotFlow { buttonCenterX }.first { it > 0f }

            // Phase 4: cursor flies to button (bezier curve)
            animateAlongCurve(buttonCenterX, buttonCenterY)

            // Phase 5: press — scale down immediately
            coroutineScope {
                launch { cursorScale.animateTo(0.55f, tween(80)) }
                launch { buttonScale.animateTo(0.94f, tween(80)) }
            }
            demoHasPlayed = true
            onDemoComplete()
        }
    }

    // ---- Button show/hide animation ----
    val atBottom = scrollProgress >= 0.85f
    var buttonComposed by remember { mutableStateOf(false) }
    val buttonAlphaAnim = remember { Animatable(0f) }

    LaunchedEffect(atBottom) {
        if (atBottom) {
            buttonComposed = true
            buttonAlphaAnim.animateTo(1f, tween(300))
        } else if (buttonComposed) {
            buttonAlphaAnim.animateTo(0f, tween(200))
            buttonComposed = false
        }
    }

    val bgColor = MaterialTheme.colorScheme.background

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor)
            .drawWithCache {
                val topGlow = Brush.radialGradient(
                    colors = listOf(Color(0x0F8C96B4), Color.Transparent),
                    center = Offset(size.width * 0.2f, size.height * 0.15f),
                    radius = size.minDimension * 0.45f,
                )
                val bottomGlow = Brush.radialGradient(
                    colors = listOf(Color(0x087882A0), Color.Transparent),
                    center = Offset(size.width * 0.8f, size.height * 0.85f),
                    radius = size.minDimension * 0.45f,
                )
                onDrawBehind {
                    drawRect(topGlow)
                    drawRect(bottomGlow)
                }
            }
            .onSizeChanged { size ->
                containerWidthPx = size.width.toFloat()
                containerHeightPx = size.height.toFloat()
            }
    ) {
        // Scrollable letter column — natural fling physics from verticalScroll
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState, enabled = interactive)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top spacer pushes first letter near screen center
            val topPadDp = with(density) {
                if (containerHeightPx > 0f) (containerHeightPx * 0.35f / density.density).dp
                else 0.dp
            }
            Spacer(modifier = Modifier.height(topPadDp))

            for (ch in LETTERS) {
                Text(
                    text = ch.toString(),
                    style = TextStyle(
                        fontSize = LetterFontSize,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.04).sp,
                        lineHeight = LetterFontSize * 0.8f,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFE8ECF2),
                                Color(0xFFB8BFC8),
                                Color(0xFF6E747C),
                            )
                        ),
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(LetterSpacing))
            }

            // Bottom spacer — full screen height so S can scroll completely off screen
            val bottomPadDp = with(density) {
                if (containerHeightPx > 0f) (containerHeightPx / density.density).dp else 0.dp
            }
            Spacer(modifier = Modifier.height(bottomPadDp))
        }

        // Top edge fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(bgColor, Color.Transparent)
                    )
                )
        )

        // Bottom edge fade
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, bgColor)
                    )
                )
        )

        // Cursor — only visible during auto-demo
        if (!interactive) {
            val cursorHalfSizePx = with(density) { 40.dp.toPx() }.roundToInt()
            Image(
                painter = painterResource(id = R.drawable.cursor),
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .zIndex(2f)
                    .offset {
                        IntOffset(
                            cursorPosX.roundToInt() - cursorHalfSizePx,
                            cursorPosY.roundToInt() - cursorHalfSizePx,
                        )
                    }
                    .graphicsLayer {
                        alpha = cursorAlpha.value
                        scaleX = cursorScale.value
                        scaleY = cursorScale.value
                        rotationZ = Math.toDegrees(
                            (cursorHeading - PointerCurveMath.IDLE_HEADING_RAD).toDouble()
                        ).toFloat()
                    }
            )
        }

        // Button — animated show/hide, removed from composition when hidden
        if (buttonComposed) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
                    .zIndex(1f)
                    .graphicsLayer {
                        alpha = buttonAlphaAnim.value
                        scaleX = buttonScale.value
                        scaleY = buttonScale.value
                    }
                    .onGloballyPositioned { coords ->
                        val parentPos = coords.positionInParent()
                        buttonCenterX = parentPos.x + coords.size.width / 2f
                        buttonCenterY = parentPos.y + coords.size.height / 2f
                    },
                contentAlignment = Alignment.Center,
            ) {
                MaterialTintLiquidButton(
                    text = stringResource(R.string.ui_onboard_startup_demo_button),
                    onClick = { onDemoComplete() },
                )
            }
        }
    }
}
