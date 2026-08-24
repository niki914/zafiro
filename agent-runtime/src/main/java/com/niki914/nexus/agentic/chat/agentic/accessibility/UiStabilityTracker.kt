package com.niki914.nexus.agentic.chat.agentic.accessibility

import android.view.accessibility.AccessibilityEvent

/**
 * Semantic strength of a single accessibility event: strong events may change
 * the model-visible UI tree, weak events are tolerated as noise.
 *
 * Public because it appears in [AccessibilityController.recordUiEvent]'s
 * public signature, which the app module calls (cross-module visibility).
 */
enum class UiEventSignificance { WEAK, STRONG }

/** Outcome of feeding one sample into the semantic stability state machine. */
internal enum class StabilityDecision { CONTINUE, SEMANTIC_STABLE }

/** Why `waitForStable` exited — used only for the temporary diagnostic log. */
internal enum class StableExitReason { EVENT_IDLE, SEMANTIC_STABLE, TIMEOUT }

/**
 * Pure classifier that grades an accessibility event's semantic strength from
 * its event type and content-change bit flags. No Android framework state is
 * read (no `event.source`, className or packageName).
 *
 * Public because the app module's NexusAccessibilityService calls it
 * (cross-module visibility).
 */
object UiEventClassifier {

    /**
     * Content-change bits that indicate a model-visible semantic change.
     * Referenced against the project's compileSdk constants; lower SDKs never
     * report the higher bits, so plain bit arithmetic is crash-safe there.
     */
    private val STRONG_BITS: Int =
        AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CONTENT_DESCRIPTION or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_CHECKED or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_STATE_DESCRIPTION or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED

    fun classify(eventType: Int, contentChangeTypes: Int): UiEventSignificance {
        return when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            -> UiEventSignificance.STRONG

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if ((contentChangeTypes and STRONG_BITS) != 0) {
                    UiEventSignificance.STRONG
                } else {
                    UiEventSignificance.WEAK
                }
            }

            // Defensive default: the production caller already filters to the
            // five types above, anything else is treated as noise.
            else -> UiEventSignificance.WEAK
        }
    }
}

/**
 * Pure state machine that declares the UI semantically stable when
 * [requiredSemanticMatches] consecutive samples carry the same semantic
 * fingerprint across at least [requiredSemanticSpanMs], without any strong
 * event arriving in between.
 *
 * Noise tolerance: when a fingerprint stays identical across
 * [noiseToleranceMatches] samples while strong events keep arriving (events
 * that never materialise as model-visible tree changes), the tree is
 * provably long-stable and the strong-event reset is bypassed. A value of 0
 * disables tolerance and keeps the strict PRD behaviour.
 */
internal class UiStabilityTracker(
    private val requiredSemanticMatches: Int = 3,
    private val requiredSemanticSpanMs: Long = 150L,
    private val noiseToleranceMatches: Int = 6,
) {
    private var previousFingerprint: Long? = null
    private var matchingSampleCount: Int = 0
    private var sequenceStartTimeMs: Long = 0L
    private var lastStrongGeneration: Long = 0L
    private var lastSampleTimeMs: Long = Long.MIN_VALUE

    /**
     * Cumulative count of samples whose fingerprint equalled the previous
     * sample's — independent of sequence resets, so sustained strong events
     * cannot starve it. Reset to 1 whenever the fingerprint changes.
     */
    private var sameFingerprintSampleCount: Int = 0

    /** True when the noise-tolerance window is active for the current sample. */
    internal fun noiseToleranceActive(): Boolean =
        noiseToleranceMatches > 0 && sameFingerprintSampleCount >= noiseToleranceMatches

    /**
     * Feeds one sample into the state machine. The five rules are evaluated
     * in order; any condition that starts a new sequence returns [StabilityDecision.CONTINUE].
     */
    fun addSample(
        fingerprint: Long,
        sampleTimeMs: Long,
        strongEventGeneration: Long,
    ): StabilityDecision {
        // Rule 1: first sample — start a new sequence.
        if (previousFingerprint == null) {
            previousFingerprint = fingerprint
            matchingSampleCount = 1
            sequenceStartTimeMs = sampleTimeMs
            lastStrongGeneration = strongEventGeneration
            lastSampleTimeMs = sampleTimeMs
            sameFingerprintSampleCount = 1
            return StabilityDecision.CONTINUE
        }

        // Cumulative stability evidence, independent of sequence resets.
        sameFingerprintSampleCount =
            if (fingerprint == previousFingerprint) sameFingerprintSampleCount + 1 else 1

        // Rule 2: clock went backwards — restart the sequence.
        if (sampleTimeMs < lastSampleTimeMs) {
            resetSequence(fingerprint, sampleTimeMs, strongEventGeneration)
            return StabilityDecision.CONTINUE
        }

        // Rule 3: a strong event arrived since the last sample.
        if (strongEventGeneration != lastStrongGeneration) {
            // Noise tolerance: the tree has stayed identical across many
            // samples while strong events kept arriving — the "event first,
            // tree later" race window (one sample period) is long past, so
            // the event is treated as noise and stability is granted.
            if (fingerprint == previousFingerprint && noiseToleranceActive()) {
                return StabilityDecision.SEMANTIC_STABLE
            }
            resetSequence(fingerprint, sampleTimeMs, strongEventGeneration)
            return StabilityDecision.CONTINUE
        }

        // Rule 4: the semantic fingerprint changed — restart.
        if (fingerprint != previousFingerprint) {
            resetSequence(fingerprint, sampleTimeMs, strongEventGeneration)
            return StabilityDecision.CONTINUE
        }

        // Rule 5: same fingerprint and no strong event — extend the run.
        matchingSampleCount += 1
        lastSampleTimeMs = sampleTimeMs
        val stable = matchingSampleCount >= requiredSemanticMatches &&
            sampleTimeMs - sequenceStartTimeMs >= requiredSemanticSpanMs
        return if (stable) StabilityDecision.SEMANTIC_STABLE else StabilityDecision.CONTINUE
    }

    private fun resetSequence(
        fingerprint: Long,
        sampleTimeMs: Long,
        strongEventGeneration: Long,
    ) {
        previousFingerprint = fingerprint
        matchingSampleCount = 1
        sequenceStartTimeMs = sampleTimeMs
        lastStrongGeneration = strongEventGeneration
        lastSampleTimeMs = sampleTimeMs
    }
}
