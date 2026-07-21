package com.niki914.nexus.agentic.app.ui.infra.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidToggleStateMachineTest {
    @Test
    fun onThumbTap_afterShortDragStillTogglesCommittedChecked() {
        val machine = LiquidToggleStateMachine(initialChecked = true)

        machine.onDragStart(fraction = 1f)
        machine.onDragProgress(fraction = 0.45f)

        val transition = machine.onThumbTap()

        assertEquals(
            LiquidToggleInteractionState.Settled(checked = false),
            transition.state,
        )
        assertEquals(false, transition.commitChecked)
        assertFalse(transition.isDragging)
    }

    @Test
    fun onThumbTap_afterDragReleaseTogglesVisualTargetBeforeExternalConfirmation() {
        val machine = LiquidToggleStateMachine(initialChecked = false)

        machine.onDragStart(fraction = 0f)
        machine.onDragProgress(fraction = 0.8f)
        val release = machine.onDragStop()
        val tap = machine.onThumbTap()

        assertEquals(true, release.commitChecked)
        assertEquals(
            LiquidToggleInteractionState.Settled(checked = false),
            tap.state,
        )
        assertEquals(false, tap.commitChecked)
        assertFalse(tap.isDragging)
    }

    @Test
    fun onExternalCheckedChange_whileDragging_resetsToSettledState() {
        val machine = LiquidToggleStateMachine(initialChecked = false)

        machine.onDragStart(fraction = 0.2f)
        val transition = machine.onExternalCheckedChange(checked = true)

        assertEquals(
            LiquidToggleInteractionState.Settled(checked = true),
            transition.state,
        )
        assertNull(transition.commitChecked)
        assertFalse(transition.isDragging)
        assertTrue(liquidToggleVisualChecked(checked = false, state = transition.state))
    }

    @Test
    fun onDragProgress_onlyEmitsThresholdTickWhenCrossingMidpoint() {
        val machine = LiquidToggleStateMachine(initialChecked = false)

        machine.onDragStart(fraction = 0.2f)
        val beforeCross = machine.onDragProgress(fraction = 0.45f)
        val cross = machine.onDragProgress(fraction = 0.55f)
        val afterCross = machine.onDragProgress(fraction = 0.8f)

        assertFalse(beforeCross.emitThresholdTick)
        assertTrue(cross.emitThresholdTick)
        assertFalse(afterCross.emitThresholdTick)
        assertEquals(
            LiquidToggleInteractionState.Dragging(
                fraction = 0.8f,
                thresholdChecked = true,
            ),
            afterCross.state,
        )
    }

    @Test
    fun onDragStop_commitsTargetCheckedAndReleaseAnimationOnlySettlesVisualState() {
        val machine = LiquidToggleStateMachine(initialChecked = false)

        machine.onDragStart(fraction = 0.2f)
        machine.onDragProgress(fraction = 0.8f)
        val releasing = machine.onDragStop()
        val settled = machine.onReleaseAnimationFinished()

        assertEquals(
            LiquidToggleInteractionState.Releasing(
                targetChecked = true,
                targetFraction = 1f,
            ),
            releasing.state,
        )
        assertEquals(true, releasing.commitChecked)
        assertFalse(releasing.isDragging)
        assertEquals(
            LiquidToggleInteractionState.Settled(checked = true),
            settled.state,
        )
        assertNull(settled.commitChecked)
    }

    @Test
    fun onPressEnd_withoutDragReturnsToConfirmedChecked() {
        val machine = LiquidToggleStateMachine(initialChecked = false)

        val pressed = machine.onPress(fraction = 0f)
        val settled = machine.onPressEnd()

        assertEquals(
            LiquidToggleInteractionState.Pressed(
                checked = false,
                fraction = 0f,
            ),
            pressed.state,
        )
        assertEquals(
            LiquidToggleInteractionState.Settled(checked = false),
            settled.state,
        )
        assertNull(settled.commitChecked)
    }
}
