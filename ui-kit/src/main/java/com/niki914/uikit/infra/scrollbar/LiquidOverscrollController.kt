package com.niki914.uikit.infra.scrollbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 刚体平移超滚控制器（PRD 第 1.2 节与第 5 节规范）：
 * - 拦截未消费的滑动量，实施阻尼纯平移，杜绝局部拉伸形变。
 * - 越界拉扯松手后，动量 100% 耗散归零，临界单向归位，绝不穿透边界。
 */
@Stable
class LiquidOverscrollController internal constructor(
    private val scope: CoroutineScope,
) {
    /** 刚体平移超滚量（px）。正数 = 触顶下拉越界；负数 = 触底上拉越界。 */
    val overscrollOffset = Animatable(0f)

    /** 当前视口高度，供阻尼缩放与收敛使用 */
    var viewportHeightPx by mutableFloatStateOf(0f)

    /** 是否正在处于回弹收敛状态 */
    var isRebounding by mutableStateOf(false)
        private set

    val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            val current = overscrollOffset.value
            if (current == 0f) return Offset.Zero

            // 若当前正处于超滚中，用户反向拉扯时优先抵消超滚量，直至完全回到边界
            return if (current > 0f && available.y < 0f) {
                val consumedY = available.y.coerceAtLeast(-current)
                scope.launch { overscrollOffset.snapTo(current + consumedY) }
                Offset(0f, consumedY)
            } else if (current < 0f && available.y > 0f) {
                val consumedY = available.y.coerceAtMost(-current)
                scope.launch { overscrollOffset.snapTo(current + consumedY) }
                Offset(0f, consumedY)
            } else {
                Offset.Zero
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (available.y == 0f) return Offset.Zero

            val current = overscrollOffset.value
            val vH = if (viewportHeightPx > 0f) viewportHeightPx else 1000f
            // 橡皮筋阻尼：拉扯位移越大，阻力非线性增大
            val rubberScale = (1f - abs(current) / (vH * 0.9f)).coerceIn(0.12f, 1f)
            val delta = available.y * rubberScale * LiquidScrollbarDefaults.RubberBandDamping

            scope.launch {
                overscrollOffset.snapTo(current + delta)
            }
            // 消费由容器层 LocalOverscrollFactory 统一屏蔽；此处返回 Zero，杜绝将超滚量作为已消费滚动上报给父级
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // PRD 5.1: 越界动量归零 (Zero Momentum Carryover)
            // 在触顶或触底处于拉扯态松手后，手势剩余速度直接清零，严禁转化为反向动量
            if (abs(overscrollOffset.value) > 0.5f) {
                animateReturn()
                return available // 拦截消耗全部惯性动量
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // 弹弓手感：快速甩动撞界时，将冲刺速度作为初速度射入临界阻尼弹簧，
            // 顺滑冲入超滚区最远端，并在动能耗尽后自然单向归位至 0，绝无急刹撞墙感
            if (available.y != 0f) {
                isRebounding = true
                try {
                    overscrollOffset.animateTo(
                        targetValue = 0f,
                        initialVelocity = available.y * 0.42f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessLow,
                        ),
                    )
                } finally {
                    isRebounding = false
                }
                return Velocity(0f, available.y)
            }
            return Velocity.Zero
        }
    }

    /** 临界单向归位动画（PRD 5.2）：到达边界硬性死锁 */
    suspend fun animateReturn() {
        isRebounding = true
        try {
            overscrollOffset.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } finally {
            isRebounding = false
        }
    }
}

@Composable
fun rememberLiquidOverscrollController(): LiquidOverscrollController {
    val scope = rememberCoroutineScope()
    return remember { LiquidOverscrollController(scope) }
}
