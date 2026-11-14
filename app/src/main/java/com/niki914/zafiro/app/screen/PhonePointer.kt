package com.niki914.zafiro.app.screen

import android.content.Context
import com.niki914.zafiro.animation.PointerCurveMath.MovementMode
import com.niki914.zafiro.api.AgentManager
import com.niki914.zafiro.api.model.isRunning
import com.niki914.zafiro.api.screen.ScreenAction
import com.niki914.zafiro.api.screen.Target
import com.niki914.zafiro.app.overlay.PointerOverlay
import com.niki914.zafiro.service.findService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicReference

internal interface PointerMotion {
    val isShowing: Boolean
    fun show(x: Float, y: Float)
    suspend fun flyTo(x: Float, y: Float)
    suspend fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long)
    fun hide()
}

internal sealed interface PhoneCue {
    data class Arrive(val x: Int, val y: Int) : PhoneCue
    data class Glide(
        val x1: Int,
        val y1: Int,
        val x2: Int,
        val y2: Int,
        val durationMs: Long,
    ) : PhoneCue

    data object Skip : PhoneCue
    data class Reject(val code: String) : PhoneCue
}

private const val SCROLL_DISTANCE_PX = 200
private const val SCROLL_DURATION_MS = 300L

/**
 * 主屏这次动作要不要动指针，以及怎么动。
 * 点击、长按、输入先飞到目标点。滚动和滑动先到起点，再沿笔画走。
 */
internal fun phoneCue(action: ScreenAction, locate: (Target) -> Pair<Int, Int>?): PhoneCue {
    return when (action) {
        is ScreenAction.Tap -> arrive(locate(action.target))
        is ScreenAction.LongClick -> arrive(locate(action.target))
        is ScreenAction.SetText -> when (action.target) {
            is Target.Point -> PhoneCue.Skip
            is Target.Node -> arrive(locate(action.target))
        }
        is ScreenAction.Scroll -> glideScroll(locate(action.target), action.forward)
        is ScreenAction.Swipe -> PhoneCue.Glide(
            action.x1,
            action.y1,
            action.x2,
            action.y2,
            action.durationMs,
        )
        else -> PhoneCue.Skip
    }
}

private fun arrive(point: Pair<Int, Int>?): PhoneCue {
    val (x, y) = point ?: return PhoneCue.Reject("NODE_STALE")
    return PhoneCue.Arrive(x, y)
}

private fun glideScroll(origin: Pair<Int, Int>?, forward: Boolean): PhoneCue {
    val (x, y) = origin ?: return PhoneCue.Reject("NODE_STALE")
    val y2 = if (forward) {
        (y - SCROLL_DISTANCE_PX).coerceAtLeast(0)
    } else {
        y + SCROLL_DISTANCE_PX
    }
    return PhoneCue.Glide(x, y, x, y2, SCROLL_DURATION_MS)
}

internal class PhonePointerPlay(
    private val motion: () -> PointerMotion?,
) {
    suspend fun <T> afterArrival(x: Float, y: Float, action: suspend () -> T): T {
        val view = motion() ?: return action()
        arrive(view, x, y)
        return action()
    }

    /**
     * 先到达笔画起点。起点到达后，指针笔画和 [action] 一起开始，并且一起结束。
     */
    suspend fun <T> glide(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long,
        action: suspend () -> T,
    ): T {
        val view = motion() ?: return action()
        arrive(view, x1, y1)
        return coroutineScope {
            val moving = launch {
                view.stroke(x1, y1, x2, y2, durationMs)
            }
            yield()
            try {
                action()
            } finally {
                moving.join()
            }
        }
    }

    private suspend fun arrive(view: PointerMotion, x: Float, y: Float) {
        if (view.isShowing) {
            view.flyTo(x, y)
        } else {
            view.show(x, y)
        }
    }
}

/**
 * 主屏指针。虚拟屏不拿这个对象。
 * 服务连上时 [attach]，对话都停了就收起。六十秒没人碰由悬浮窗自己淡出。
 */
object PhonePointer {
    private val motion = AtomicReference<PointerMotion?>(null)
    private var overlay: PointerOverlay? = null
    private var watchJob: Job? = null

    internal val play: PhonePointerPlay = PhonePointerPlay { motion.get() }

    fun attach(context: Context) {
        detach()
        val created = PointerOverlay().also { it.init(context) }
        overlay = created
        motion.set(OverlayMotion(created))
    }

    fun detach() {
        val previous = overlay
        overlay = null
        motion.set(null)
        previous?.dispose()
    }

    fun watch(scope: CoroutineScope) {
        watchJob?.cancel()
        watchJob = scope.launch {
            val manager = findService<AgentManager>() ?: return@launch
            combine(manager.control.status, manager.activeStates) { main, active ->
                main.isRunning || active.values.any { it.isRunning }
            }
                .distinctUntilChanged()
                .collect { running ->
                    if (!running) motion.get()?.hide()
                }
        }
    }

    private class OverlayMotion(private val overlay: PointerOverlay) : PointerMotion {
        override val isShowing: Boolean get() = overlay.isShowing

        override fun show(x: Float, y: Float) = overlay.show(x, y)

        override suspend fun flyTo(x: Float, y: Float) {
            overlay.animateTo(x, y, MovementMode.FLY)
        }

        override suspend fun stroke(
            x1: Float,
            y1: Float,
            x2: Float,
            y2: Float,
            durationMs: Long,
        ) = overlay.stroke(x1, y1, x2, y2, durationMs)

        override fun hide() = overlay.hide()
    }
}
