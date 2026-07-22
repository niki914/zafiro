package com.niki914.nexus.agentic.app.ui.nexus.content

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import com.niki914.nexus.agentic.app.ui.nexus.model.StartupAssistantUi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val LETTERS = "NEXUS"
private val LetterFontSize = 96.sp
private val LetterSpacing = 28.dp

private val PointerEasing: Easing by lazy {
    val lut = PointerCurveMath.buildSpeedLut()
    Easing { t -> PointerCurveMath.timeToDistance(t, lut) }
}

/** Persists across back-navigation so the demo only plays once per process lifetime. */
private var demoHasPlayed = false

@Composable
fun StartupPageContent(
    assistantUi: StartupAssistantUi,
    onDemoComplete: () -> Unit,
) {
    val density = LocalDensity.current
    val interactive = demoHasPlayed
    val scrollState = rememberScrollState()

    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    var buttonCenterX by remember { mutableFloatStateOf(0f) }
    var buttonCenterY by remember { mutableFloatStateOf(0f) }

    // Button fades in over the last 15% of scroll travel
    val scrollProgress = if (scrollState.maxValue > 0)
        scrollState.value.toFloat() / scrollState.maxValue else 0f
    val buttonAlpha = if (scrollProgress >= 0.85f) {
        ((scrollProgress - 0.85f) / 0.15f).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Cursor state — used only during auto-demo
    val cursorAlpha = remember { Animatable(0f) }
    val cursorScale = remember { Animatable(1f) }
    val cursorX = remember { Animatable(0f) }
    val cursorY = remember { Animatable(0f) }
    val buttonScale = remember { Animatable(1f) }

    // ---- Auto-demo animation (first visit only) ----
    if (!interactive) {
        LaunchedEffect(Unit) {
            // Wait for container size — the Column layout depends on it
            snapshotFlow { containerHeightPx }
                .first { it > 0f }

            val h = containerHeightPx
            val w = containerWidthPx

            // Cursor positions:
            //   1. Start at screen center
            //   2. Move down to lower area (~72% height)
            //   3. Swipe UP to upper area (~28% height)
            //   4. Fly to button (bottom)
            val centerY = h * 0.5f
            val lowerY = h * 0.72f
            val upperY = h * 0.28f

            cursorX.snapTo(w * 0.5f)
            cursorY.snapTo(centerY)

            // Phase 1: cursor fades in
            delay(500)
            cursorAlpha.animateTo(1f, tween(400))
            delay(300)

            // Phase 2: cursor moves down to lower area
            cursorY.animateTo(lowerY, tween(400, easing = PointerEasing))
            delay(200)

            // Phase 3: cursor swipes UP first, then list scrolls
            snapshotFlow { scrollState.maxValue }.first { it > 0 }
            cursorY.animateTo(upperY, tween(600, easing = PointerEasing))
            scrollState.animateScrollTo(
                scrollState.maxValue,
                tween(1100, easing = PointerEasing),
            )
            delay(200)

            // Ensure button layout coordinates are ready
            snapshotFlow { buttonCenterX }.first { it > 0f }

            // Phase 4: cursor flies to button
            coroutineScope {
                launch { cursorX.animateTo(buttonCenterX, tween(550, easing = PointerEasing)) }
                launch { cursorY.animateTo(buttonCenterY, tween(550, easing = PointerEasing)) }
            }

            // Phase 5: press — navigate immediately (no release animation, no extra delay)
            coroutineScope {
                launch { cursorScale.animateTo(0.55f, tween(80)) }
                launch { buttonScale.animateTo(0.94f, tween(80)) }
            }
            demoHasPlayed = true
            onDemoComplete()
        }
    }

    val bgColor = Color(0xFF0A0B0F)

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
            val cursorHalfSizePx = with(density) { 30.dp.toPx() }.roundToInt()
            Image(
                painter = painterResource(id = R.drawable.cursor),
                contentDescription = null,
                modifier = Modifier
                    .size(60.dp)
                    .zIndex(2f)
                    .offset {
                        IntOffset(
                            cursorX.value.roundToInt() - cursorHalfSizePx,
                            cursorY.value.roundToInt() - cursorHalfSizePx,
                        )
                    }
                    .graphicsLayer {
                        alpha = cursorAlpha.value
                        scaleX = cursorScale.value
                        scaleY = cursorScale.value
                    }
            )
        }

        // Button — only composed when visible, pinned at bottom
        if (buttonAlpha > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 56.dp, start = 24.dp, end = 24.dp)
                    .fillMaxWidth()
                    .zIndex(1f)
                    .graphicsLayer {
                        alpha = buttonAlpha
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
                    text = "开始使用",
                    onClick = { onDemoComplete() },
                )
            }
        }
    }
}
