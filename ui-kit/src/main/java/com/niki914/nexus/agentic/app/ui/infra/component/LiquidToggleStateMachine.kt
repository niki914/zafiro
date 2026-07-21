package com.niki914.nexus.agentic.app.ui.infra.component

sealed interface LiquidToggleInteractionState {
    data class Settled(
        val checked: Boolean,
    ) : LiquidToggleInteractionState

    data class Pressed(
        val checked: Boolean,
        val fraction: Float,
    ) : LiquidToggleInteractionState

    data class Dragging(
        val fraction: Float,
        val thresholdChecked: Boolean,
    ) : LiquidToggleInteractionState

    data class Releasing(
        val targetChecked: Boolean,
        val targetFraction: Float,
    ) : LiquidToggleInteractionState
}

data class LiquidToggleTransition(
    val state: LiquidToggleInteractionState,
    val commitChecked: Boolean? = null,
    val isDragging: Boolean = false,
    val emitThresholdTick: Boolean = false,
)

fun liquidToggleVisualChecked(
    checked: Boolean,
    state: LiquidToggleInteractionState?,
): Boolean {
    return when (state) {
        null -> checked
        is LiquidToggleInteractionState.Settled -> state.checked
        is LiquidToggleInteractionState.Pressed -> state.checked
        is LiquidToggleInteractionState.Dragging -> state.thresholdChecked
        is LiquidToggleInteractionState.Releasing -> state.targetChecked
    }
}

class LiquidToggleStateMachine(
    initialChecked: Boolean,
) {
    private var confirmedChecked: Boolean = initialChecked
    private var state: LiquidToggleInteractionState =
        LiquidToggleInteractionState.Settled(checked = initialChecked)

    fun onExternalCheckedChange(checked: Boolean): LiquidToggleTransition {
        confirmedChecked = checked
        val nextState = LiquidToggleInteractionState.Settled(checked = checked)
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            isDragging = false,
        )
    }

    fun onPress(fraction: Float): LiquidToggleTransition {
        val nextState =
            LiquidToggleInteractionState.Pressed(
                checked = visualChecked(),
                fraction = fraction,
            )
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            isDragging = false,
        )
    }

    fun onPressEnd(): LiquidToggleTransition {
        val nextState = LiquidToggleInteractionState.Settled(checked = confirmedChecked)
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            isDragging = false,
        )
    }

    fun onThumbTap(): LiquidToggleTransition {
        val baseChecked =
            when (val current = state) {
                is LiquidToggleInteractionState.Releasing -> current.targetChecked
                else -> confirmedChecked
            }
        val nextChecked = !baseChecked
        val nextState = LiquidToggleInteractionState.Settled(checked = nextChecked)
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            commitChecked = nextChecked,
            isDragging = false,
        )
    }

    fun onDragStart(fraction: Float): LiquidToggleTransition {
        val nextState =
            LiquidToggleInteractionState.Dragging(
                fraction = fraction,
                thresholdChecked = fraction >= MIDPOINT_FRACTION,
            )
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            isDragging = true,
        )
    }

    fun onDragProgress(fraction: Float): LiquidToggleTransition {
        val previousThresholdChecked =
            (state as? LiquidToggleInteractionState.Dragging)?.thresholdChecked
                ?: (fraction >= MIDPOINT_FRACTION)
        val nextThresholdChecked = fraction >= MIDPOINT_FRACTION
        val nextState =
            LiquidToggleInteractionState.Dragging(
                fraction = fraction,
                thresholdChecked = nextThresholdChecked,
            )
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            isDragging = true,
            emitThresholdTick = previousThresholdChecked != nextThresholdChecked,
        )
    }

    fun onDragStop(): LiquidToggleTransition {
        val nextChecked =
            when (val current = state) {
                is LiquidToggleInteractionState.Settled -> current.checked
                is LiquidToggleInteractionState.Pressed -> current.checked
                is LiquidToggleInteractionState.Dragging -> current.fraction >= MIDPOINT_FRACTION
                is LiquidToggleInteractionState.Releasing -> current.targetChecked
            }
        val nextFraction = if (nextChecked) 1f else 0f
        val nextState =
            LiquidToggleInteractionState.Releasing(
                targetChecked = nextChecked,
                targetFraction = nextFraction,
            )
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            commitChecked = nextChecked,
            isDragging = false,
        )
    }

    fun onReleaseAnimationFinished(): LiquidToggleTransition {
        val nextChecked =
            when (val current = state) {
                is LiquidToggleInteractionState.Settled -> current.checked
                is LiquidToggleInteractionState.Pressed -> current.checked
                is LiquidToggleInteractionState.Dragging -> current.thresholdChecked
                is LiquidToggleInteractionState.Releasing -> current.targetChecked
            }
        val nextState = LiquidToggleInteractionState.Settled(checked = nextChecked)
        state = nextState
        return LiquidToggleTransition(
            state = nextState,
            isDragging = false,
        )
    }

    private fun visualChecked(): Boolean {
        return when (val current = state) {
            is LiquidToggleInteractionState.Settled -> current.checked
            is LiquidToggleInteractionState.Pressed -> current.checked
            is LiquidToggleInteractionState.Dragging -> current.thresholdChecked
            is LiquidToggleInteractionState.Releasing -> current.targetChecked
        }
    }

    private companion object {
        private const val MIDPOINT_FRACTION = 0.5f
    }
}
