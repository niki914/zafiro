package com.niki914.nexus.agentic.chat.agentic.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [UiStabilityTracker] (8 state-machine cases) and
 * [UiEventClassifier] (10 classification cases).
 *
 * Classification tests use magic bit values, independent of direct Android
 * constant references; the values mirror the compileSdk (37) constants that
 * the production code uses:
 *   TEXT = 0x0002, CONTENT_DESCRIPTION = 0x0004, CHECKED = 0x2000,
 *   SUBTREE = 0x0001, UNDEFINED = 0.
 */
class UiStabilityTrackerTest {

    // Magic event-type values (AccessibilityEvent constants, kept local so
    // the tests stay independent of the Android framework).
    private val TYPE_WINDOW_STATE_CHANGED = 32
    private val TYPE_VIEW_TEXT_CHANGED = 16
    private val TYPE_WINDOW_CONTENT_CHANGED = 2048
    private val TYPE_WINDOWS_CHANGED = 4194304
    private val TYPE_VIEW_SCROLLED = 4096

    // Magic content-change bit values (mirroring compileSdk 37 constants:
    // SUBTREE=0x0001, TEXT=0x0002, CONTENT_DESCRIPTION=0x0004, CHECKED=0x2000).
    private val BIT_TEXT = 0x0002
    private val BIT_CONTENT_DESCRIPTION = 0x0004
    private val BIT_CHECKED = 0x2000
    private val BIT_SUBTREE = 0x0001

    // ---------------------------------------------------------------
    // UiStabilityTracker — state machine (8 cases)
    // ---------------------------------------------------------------

    @Test
    fun firstSample_continues() {
        val tracker = UiStabilityTracker()
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1000L, 0L))
    }

    @Test
    fun twoIdenticalSamplesSameTimestamp_continues() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1000L, 0L))
    }

    @Test
    fun threeIdenticalSamplesSpanBelow150ms_continues() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(1L, 1050L, 0L)
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1100L, 0L))
    }

    @Test
    fun threeIdenticalSamplesSpanAtLeast150ms_semanticStable() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(1L, 1100L, 0L)
        assertEquals(StabilityDecision.SEMANTIC_STABLE, tracker.addSample(1L, 1200L, 0L))
    }

    @Test
    fun fingerprintChange_resetsSequence() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(2L, 1050L, 0L) // fingerprint change -> new sequence
        // count=2 and span=100ms: without the reset this would already be
        // SEMANTIC_STABLE (count=3, span=150ms).
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(2L, 1150L, 0L))
    }

    @Test
    fun generationChangeSameFingerprint_resetsSequence() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(1L, 1050L, 0L)
        tracker.addSample(1L, 1100L, 1L) // strong event arrived -> new sequence
        // count=2 and span=100ms: without the reset this would already be
        // SEMANTIC_STABLE (count=4, span=200ms).
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1200L, 1L))
    }

    @Test
    fun generationUnchanged_weakEventNoiseDoesNotBlockStability() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(1L, 1100L, 0L) // generation unchanged (weak events only)
        assertEquals(StabilityDecision.SEMANTIC_STABLE, tracker.addSample(1L, 1200L, 0L))
    }

    // ---------------------------------------------------------------
    // UiStabilityTracker — noise tolerance (5 cases)
    // ---------------------------------------------------------------

    @Test
    fun sustainedGenerationChanges_stableFingerprint_grantsStableAfterTolerance() {
        // One strong event per sample while the tree never changes: the
        // cumulative same-fingerprint evidence reaches the tolerance window
        // (6) and stability is granted despite the ongoing generation bumps.
        val tracker = UiStabilityTracker()
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1000L, 0L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1100L, 1L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1200L, 2L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1300L, 3L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1400L, 4L))
        assertEquals(StabilityDecision.SEMANTIC_STABLE, tracker.addSample(1L, 1500L, 5L))
    }

    @Test
    fun sustainedGenerationChanges_belowTolerance_continues() {
        val tracker = UiStabilityTracker()
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1000L, 0L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1100L, 1L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1200L, 2L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1300L, 3L))
        // sameCount == 5 < 6: tolerance not yet active, still resetting.
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1400L, 4L))
    }

    @Test
    fun noiseToleranceDisabled_generationAlwaysResets() {
        val tracker = UiStabilityTracker(noiseToleranceMatches = 0)
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1000L, 0L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1100L, 1L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1200L, 2L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1300L, 3L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1400L, 4L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1500L, 5L))
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1600L, 6L))
    }

    @Test
    fun noiseToleranceGranted_fingerprintChangeStillResets() {
        // After a tolerance grant, a real tree change must still reset the
        // evidence and the sequence.
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(1L, 1100L, 1L)
        tracker.addSample(1L, 1200L, 2L)
        tracker.addSample(1L, 1300L, 3L)
        tracker.addSample(1L, 1400L, 4L)
        assertEquals(StabilityDecision.SEMANTIC_STABLE, tracker.addSample(1L, 1500L, 5L))
        // Fingerprint changed and the generation still differs: reset, not grant.
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(2L, 1600L, 5L))
    }

    @Test
    fun noiseToleranceActive_flagReflectsThreshold() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        assertEquals(false, tracker.noiseToleranceActive())
        tracker.addSample(1L, 1100L, 1L)
        tracker.addSample(1L, 1200L, 2L)
        tracker.addSample(1L, 1300L, 3L)
        tracker.addSample(1L, 1400L, 4L)
        assertEquals(false, tracker.noiseToleranceActive()) // sameCount == 5
        tracker.addSample(1L, 1500L, 5L)
        assertEquals(true, tracker.noiseToleranceActive()) // sameCount == 6
    }

    @Test
    fun timeRegression_resetsSequence() {
        val tracker = UiStabilityTracker()
        tracker.addSample(1L, 1000L, 0L)
        tracker.addSample(1L, 1100L, 0L)
        tracker.addSample(1L, 900L, 0L) // clock went backwards -> new sequence
        tracker.addSample(1L, 950L, 0L)
        // count=3 but span=100ms: without the reset this would already be
        // SEMANTIC_STABLE (count=5).
        assertEquals(StabilityDecision.CONTINUE, tracker.addSample(1L, 1000L, 0L))
    }

    // ---------------------------------------------------------------
    // UiEventClassifier — event classification (10 cases)
    // ---------------------------------------------------------------

    @Test
    fun classify_windowStateChanged_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_WINDOW_STATE_CHANGED, 0),
        )
    }

    @Test
    fun classify_windowsChanged_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_WINDOWS_CHANGED, 0),
        )
    }

    @Test
    fun classify_viewScrolled_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_VIEW_SCROLLED, 0),
        )
    }

    @Test
    fun classify_viewTextChanged_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_VIEW_TEXT_CHANGED, 0),
        )
    }

    @Test
    fun classify_contentChangedWithTextBit_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_WINDOW_CONTENT_CHANGED, BIT_TEXT),
        )
    }

    @Test
    fun classify_contentChangedWithContentDescriptionBit_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_WINDOW_CONTENT_CHANGED, BIT_CONTENT_DESCRIPTION),
        )
    }

    @Test
    fun classify_contentChangedWithCheckedBit_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_WINDOW_CONTENT_CHANGED, BIT_CHECKED),
        )
    }

    @Test
    fun classify_contentChangedWithSubtreeBit_weak() {
        assertEquals(
            UiEventSignificance.WEAK,
            UiEventClassifier.classify(TYPE_WINDOW_CONTENT_CHANGED, BIT_SUBTREE),
        )
    }

    @Test
    fun classify_contentChangedUndefined_weak() {
        assertEquals(
            UiEventSignificance.WEAK,
            UiEventClassifier.classify(TYPE_WINDOW_CONTENT_CHANGED, 0),
        )
    }

    @Test
    fun classify_contentChangedSubtreeOrText_strong() {
        assertEquals(
            UiEventSignificance.STRONG,
            UiEventClassifier.classify(TYPE_WINDOW_CONTENT_CHANGED, BIT_SUBTREE or BIT_TEXT),
        )
    }
}
