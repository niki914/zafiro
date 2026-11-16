---
name: phone-use
description: MUST load for any app or screen tasks. Guides on screenshots, tapping, swiping, and UI interactions.
---

## Overview

You interact with Android screens (`vd-*` or `phone`) across two layers:
- **Semantic tree**: Inspect hierarchy, search text, and tap/type/scroll by node token.
- **Visual pixels**: Capture screenshots when the tree is unavailable, then tap or swipe by coordinates.

Write actions automatically settle the UI and return the refreshed tree with a new version token — never issue a redundant read immediately after an action.

## Screen Policy: Virtual First

Always prioritize non-intrusive execution:

1. **Default to virtual display (`vd-*`)**:
   - Create a virtual screen using `screens`. It runs the app in the background and displays a floating preview card so the user can observe without interruption.
   - Launch the target app onto the virtual display with `open` after `find_installed_apps` returns its package name.
   - **Always teardown**: Release the screen via `screens` when the task finishes or aborts to dismiss the floating card.
2. **Fallback to physical screen (`phone`)**:
   - Use the main screen only when the user explicitly requests it or when virtual display creation fails.

## Interaction Strategy

### 1. Semantic Tree vs. Visual Coordinates

- **Tree-first**: Prefer targeting nodes by `token` (`{version}_{index}`). The framework automatically handles node clicks and gesture fallbacks.
- **Dense trees**: Use `search` with keywords rather than scanning huge hierarchy dumps.
- **Custom-drawn apps (Canvas / Flutter / Unity)**: If the tree is empty or root-only, switch to visual mode immediately: capture a `screenshot` and act via physical coordinates.
- **Coordinate scaling**: Screenshots may be downscaled. Screen dimensions are in the tree header (`screen: [w, h]`) or `screens` list. Always scale image coordinates to screen pixels:
   $$\text{screen\_x} = \text{image\_x} \times \frac{\text{screen\_w}}{\text{image\_w}}$$
   $$\text{screen\_y} = \text{image\_y} \times \frac{\text{screen\_h}}{\text{image\_h}}$$

### 2. Scrolling & Swiping Heuristics

- **Avoid edge traps**: Never start gestures at screen edges. Bottom navigation bars, docks, and mini-players steal touch-down. Start mid-container (~70% container height) and swipe upward to scroll down.
- **Exit condition**: Define an observable termination condition (target item visible, section header, or count) before scrolling.
- **Detect list boundary**: If two consecutive swipes leave the visible hierarchy unchanged and no loading indicator is active, the list boundary is reached.
- **Pacing**: One swipe per decision when hunting unknown items. Batching 2–3 swipes is only acceptable when skipping past known content.

### 3. Typing & Verification

- **Typing**: Text injection targets a node `token`. Tap the field first if focus is needed.
- **State verification**: A successful status confirms event injection, not that the application reached the desired semantic state. Always verify against the returned snapshot.
- **Stale tokens**: If an action fails due to a stale token, the UI changed. Use the fresh token from the returned snapshot or switch to coordinates.

## Failure & Consent

If a required permission is missing or an operation is rejected by the system, explain the issue to the user and halt execution. Never retry failing actions in an infinite loop.

## App Traps

When an unexpected result reveals a stable, verified UI anomaly (misleading clickable wrapper, phantom overlay, deceptive bounds), record it with `memory` as a single declarative sentence (naming the app, element, and workaround). Do not store temporary task progress.
