---
name: Phone Use
description: MUST Load this skill for ANY task that involves GUI interaction or device control —
  opening apps, tapping, typing, scrolling, swiping, Back/Home/Recents, reading the screen,
  or any workflow that touches on-screen content. Load this even when the user does not
  explicitly mention the device — if the task implies a GUI operation, load this skill first.
---

## Overview

You can see and control the Android screen through three channels:

- **The UI tree** — `screen_operation_accessibility` reads the semantic tree (text, labels, bounds, clickability) and acts on nodes by token.
- **Pixels** — `screenshot` captures the screen as an image and `view_image` reads image files, so you can visually inspect any app.
- **Coordinates** — `screen_operation_shell` injects tap/swipe/key events at screen-pixel coordinates.

Use the tree first; drop to pixels and coordinates when the tree cannot see or reach the target. Coordinates may come from node bounds **or from a screenshot** — compute them, never guess.

First-use consent: when the accessibility or overlay permission is missing, the first screen tool call asks the user to grant both. If the user declines, the tool fails with a consent message — tell the user what to enable and stop; do not retry in a loop. Root is not required; it only shortens the permission chain.

## Workflow

### 1. Open the target

Prefer `open_uri` when a deep link lands closer to the goal than the launcher activity (e.g. a map at coordinates, a settings page). Otherwise resolve with `find_installed_apps` if the name is ambiguous, then `launch_app`.

### 2. Read the screen

Start with `screen_operation_accessibility(operation: "read")`. If the tree is empty or root-only, the app uses a non-native UI (Flutter, Unity, WebView, self-drawn canvas) — the tree will not work for this app. Do not retry the read. Instead switch to `screenshot` + coordinate operations via `screen_operation_shell`.

### 3. Act

- Tree shows a labeled, clickable node → tap it by token.
- Target is unlabeled, a bare container, or the node's bounds look misaligned with what you see → take a `screenshot`, locate the real target visually, convert image coordinates to screen pixels (see below), and tap with `screen_operation_shell`.
- `set_text` needs the accessibility method; there is no shell fallback for typing.

**Image → screen coordinates.** The screenshot is downscaled; its `width`/`height` in the result are the image's, not the screen's. Screen dimensions are in the tree header. Scale per axis: `screen_x = image_x * screen_w / image_w` (same for y).

### 4. Verify

Write operations return the updated tree automatically. Check that the UI actually reached the expected state — a `success` status means the event was injected, not that the app reacted. If the screen did not change as expected, re-examine: the target may be self-drawn (bounds unreliable — see §3), the tap may have landed on an overlay, or the app may need more time (`wait_ms`). On `SHELL_TIMEOUT` or `SHELL_SESSION_LOST`, verify the screen before retrying as the action may have partially executed.

### 5. Scroll

Scroll lists with `screen_operation_shell(operation: "swipe", ...)` — accessibility scroll steps are app-defined and unreliable. Derive coordinates from the scrollable container's bounds, swiping ~70% of the container. Start the gesture mid-container, not at the bottom edge: bottom-docked bars (tab bars, mini-players, toolbars) steal the touch-down. If a dock's bounds contain the start point, move the start above its top edge.

### 6. Navigate

Use key events via `screen_operation_shell`: Back (`code: 4`), Home (`code: 3`), Recents (`code: 187`).

## Scrolling heuristics

- Before scrolling a list, define the exit condition in observable UI: a result count, an empty state, a section boundary.
- After two separate swipes the visible items are unchanged and nothing is loading → the list boundary is reached. Retarget once in case the gesture missed the container before concluding.
- One swipe per decision when hunting a specific item; batching 2–3 swipes is acceptable only when skipping intermediate states cannot miss the target.

## App traps

When an unexpected result reveals a stable, verified rule about an app's UI, save it with `memory` (one natural-language sentence naming the app, the misleading element, and the correct action). Do not memorize temporary state, the current tab, or unverified guesses.
