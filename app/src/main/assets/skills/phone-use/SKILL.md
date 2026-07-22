---
name: Phone Use
description: MUST Load this skill for ANY task that involves GUI interaction or device control —
  opening apps, tapping, typing, scrolling, swiping, Back/Home/Recents, reading the screen,
  or any workflow that touches on-screen content. Load this even when the user does not
  explicitly mention the device — if the task implies a GUI operation, load this skill first.
---

## Overview

Phone Use gives you the ability to see and interact with the Android device screen. It uses Android AccessibilityService (with root-assisted auto-setup) to read the UI tree and simulate user actions.

This is the ONLY way to control the device — do NOT attempt to use non-existent APIs or platform tools.

**When to load:** any GUI task. App launching, tapping, typing, scrolling, swiping, navigation keys, screen reading. Even when the user doesn't say "on the phone" — infer it from the task.

**When NOT to retry:** non-native apps (Flutter, Unity, WebView, games). If `screen_content` returns empty/root-only, stop and report immediately.

Four core tools:

| Tool | Purpose |
|:-----|:--------|
| `screen_content` | Read the current screen's accessibility tree as YAML |
| `search_nodes` | Search the current screen for nodes matching keywords. Returns matched indices for use with `node_action` |
| `node_action` | Click, long-click, set text, or scroll on a UI node by its index |
| `gesture` | Swipe/drag by screen coordinates |
| `key_event` | Inject system key events (Back, Home, Recents, etc.) |

Two auxiliary tools for app discovery and launching:

| Tool | Purpose |
|:-----|:--------|
| `search_apps` | Search installed apps by name or package name |
| `launch_app` | Launch an app by package name or fuzzy app name |

## Tool Reference

### screen_content

```
screen_content()
```

Returns the current screen's accessibility tree as a YAML string. No arguments.

**Response format:** The tool returns raw YAML output. On failure, the string starts with `error: `.

**Non-native app detection:** If the tree is empty or contains only a root node, the current app likely uses a non-native UI framework (Flutter, Unity, WebView, game engine) that does not expose standard Android accessibility node trees. No amount of retrying will help. **Stop immediately and report to the user.**

### search_nodes

```
search_nodes(keywords: string[], match_mode?: string, limit?: integer)
```

| Param | Type | Required | Description |
|:------|:-----|:---------|:------------|
| `keywords` | string[] | yes | Keywords for case-insensitive substring matching on node text (`txt`) and content description (`h`) |
| `match_mode` | string | no | `"any"` (default, match any keyword) or `"all"` (match all keywords) |
| `limit` | integer | no | Maximum results to return (default 10) |

```json
{"keywords": ["收藏", "我的"]}
{"keywords": ["settings"], "match_mode": "all", "limit": 5}
```

Use `search_nodes` when the screen has many nodes and you need to locate specific UI elements by their text or description. It returns a concise YAML list of matching nodes with their indices, types, bounds, and positions — use the returned indices directly with `node_action`. This is faster than parsing the full `screen_content` tree.

**Response format:** The tool returns raw YAML output:
```yaml
matched: 3
nodes:
  - {i: 12, t: tab, b: [0,2160,360,2400], pos: bottom-left, txt: 收藏, tap: true}
  - {i: 15, t: tab, b: [360,2160,720,2400], pos: bottom, txt: 我的, tap: true}
```

If no nodes match, `matched: 0` with an empty `nodes:` section. On failure, the string starts with `error: `.

### node_action

```
node_action(action: string, index: integer, text?: string, method?: string)
```

| Param | Type | Required | Description |
|:------|:-----|:---------|:------------|
| `action` | string | yes | `click`, `long_click`, `set_text`, `scroll_forward`, `scroll_backward` |
| `index` | integer | yes | Node index from the latest `screen_content` output (the `i` field) |
| `text` | string | only `set_text` | Text to type into an input field |
| `method` | string | no | `accessibility` (default) or `shell` |

```json
{"action": "click", "index": 42}
{"action": "set_text", "index": 7, "text": "hello"}
{"action": "scroll_forward", "index": 15}
{"action": "long_click", "index": 3, "method": "shell"}
```

**Method rules:**
- `set_text` **only works with `accessibility`** (the default). Shell cannot type into text fields — the tool rejects it with `METHOD_NOT_SUPPORTED`.
- `accessibility` tries the accessibility action first; non-set_text actions auto-fall-back to shell on failure.
- `shell` uses `su -c input tap/swipe` directly, bypassing the accessibility service. Use it when the service is unavailable or as an explicit escape hatch.

**Scroll limitation:** `scroll_forward` and `scroll_backward` move **1 step** per call, but the step size depends on the app's accessibility implementation and varies unpredictably — sometimes half a screen, sometimes the entire list. Prefer `gesture` with calculated coordinates for list scrolling (see Workflow §4). Use `scroll_forward`/`scroll_backward` only for single-step increments in pickers or small scrollable widgets.

### gesture

```
gesture(start_x: number, start_y: number, end_x: number, end_y: number, duration?: integer, method?: string)
```

| Param | Type | Required | Description |
|:------|:-----|:---------|:------------|
| `start_x` | number | yes | X coordinate of gesture start |
| `start_y` | number | yes | Y coordinate of gesture start |
| `end_x` | number | yes | X coordinate of gesture end |
| `end_y` | number | yes | Y coordinate of gesture end |
| `duration` | integer | no | Duration in milliseconds (default 300) |
| `method` | string | no | `accessibility` (default) or `shell` |

```json
{"start_x": 100, "start_y": 500, "end_x": 100, "end_y": 200}
{"start_x": 540, "start_y": 1500, "end_x": 540, "end_y": 500, "duration": 500}
```

Use `gesture` for swipes (vertical/horizontal scroll, drag-and-drop) that don't target a single node. For tapping a labeled UI element, prefer `node_action` with its index.

### key_event

```
key_event(key: integer)
```

| Param | Type | Required | Description |
|:------|:-----|:---------|:------------|
| `key` | integer | yes | Android key code |

Standard key codes:

| Code | Key |
|:-----|:----|
| 3 | HOME |
| 4 | BACK |
| 83 | NOTIFICATIONS |
| 84 | QUICK_SETTINGS |
| 187 | RECENTS / APP_SWITCH |

```json
{"key": 4}
```

Other numeric key codes (volume, camera, media controls) are supported via shell fallback.

### launch_app

```
launch_app(package_name?: string, app_name?: string)
```

Launch an app by exact `package_name` or fuzzy `app_name`.

```json
{"package_name": "com.tencent.mm"}
{"app_name": "Chrome"}
```

If `app_name` matches multiple apps, the tool returns a candidate list instead of launching. Pick one `package_name` from the candidates and call again.

### search_apps

```
search_apps(query: string, include_system?: boolean, limit?: integer)
```

Search installed apps by name or package name fragment. Use before `launch_app` when the target app name is ambiguous.

```json
{"query": "微信"}
{"query": "settings", "include_system": true, "limit": 5}
```

## Workflow

### 1. Open the target app

If the task names a specific app, launch it:

```
launch_app(app_name: "Settings")
```

If ambiguous, search first then launch by package name:

```
search_apps(query: "settings")
launch_app(package_name: "com.android.settings")
```

### 2. Read the screen

After the app is open (and after every action), call `screen_content`:

```
screen_content()
```

This returns a YAML accessibility tree. Each node has an index (`i`) you use with `node_action`.

### 2b. Search for specific elements (optional)

When the screen has many nodes (e.g., a long list or a complex layout) and you know what text or label you're looking for, use `search_nodes` to narrow down candidate indices:

```
search_nodes(keywords: ["目标文本"])
```

This returns a short list of matching nodes with their indices. Use the returned index directly with `node_action`. This avoids scanning the full tree.

After acting on a search result, always re-read with `screen_content` before the next action — indices from `search_nodes` are also ephemeral.

### 3. Act on the tree

Use `node_action` with the node's index:

```
node_action(action: "click", index: 42)
node_action(action: "set_text", index: 7, text: "hello")
```

For scrollable lists, prefer `gesture` with calculated coordinates — see §4 for the formula. `scroll_forward`/`scroll_backward` step sizes are unreliable across apps.

For actions that don't target a single labeled node (e.g., swipe-to-refresh, drag items), use `gesture` with coordinates.

### 4. Re-read after state-changing actions

**Re-read `screen_content` after actions that change screen state:** clicks, `launch_app`, `set_text`, `key_event` navigation. Indices are freshly assigned on every `screen_content` call and stale immediately. Never reuse an index from a previous screen read.

**Scrolling lists — use `gesture`, not `scroll_forward`/`scroll_backward`.** The accessibility scroll step size depends on the app's container implementation and is unreliable — sometimes half a screen, sometimes the entire list. `gesture` gives pixel-precise control.

Calculate coordinates from the screen dimensions in the `screen_content` header:
- **Vertical scroll:** `gesture(start_x: w/2, start_y: h*0.7, end_x: w/2, end_y: h*0.1)` — swipes ~60% of screen height
- **Horizontal scroll:** `gesture(start_x: w*0.7, start_y: h/2, end_x: w*0.1, end_y: h/2)` — swipes ~60% of screen width

The 30-40% overlap keeps enough of the previous view as an anchor so you can track where you are. Batch 2-3 swipes before re-reading `screen_content`.

### 5. Navigate between apps

```
key_event(key: 4)   // Back
key_event(key: 3)   // Home
key_event(key: 187) // App switcher
```

## Accessibility Tree YAML Format

The `screen_content` output header includes the foreground app package and screen dimensions. Each node uses these fields:

| Field | Meaning |
|:------|:--------|
| `i` | Node index — use with `node_action` |
| `t` | Semantic type: `button`, `input`, `text`, `image`, `list`, `list_item`, `switch`, `checkbox`, `tab`, `chip`, `toolbar`, `dialog`, `container` |
| `b` | Bounds `[left, top, right, bottom]` in pixels |
| `pos` | 3×3 grid position: `top-left`, `top`, `top-right`, `left`, `center`, `right`, `bottom-left`, `bottom`, `bottom-right` |
| `txt` | Display text (quoted if it contains special characters) |
| `h` | Content description / accessibility identifier |
| `tap` | Node is clickable (only shown when true) |
| `hold` | Node is long-clickable (only shown when true) |
| `edit` | Node is editable text input (only shown when true) |
| `scroll` | Node is scrollable (only shown when true) |
| `checked` | Node has checked state — switches, checkboxes (only shown when true) |
| `ch` | Child nodes |
| `more` | Summaries of off-screen children (text or `(empty)`), each truncated to 20 characters |

Boolean attributes (`tap`, `hold`, `edit`, `scroll`, `checked`) are only emitted when true. If absent, the attribute is false.

## Method Selection

| If you need to... | Use |
|:------------------|:----|
| Type text into a field | `accessibility` (only option) |
| Tap a labeled UI element | `accessibility` (default, auto-fallbacks) |
| Scroll a list | `gesture` with calculated coordinates. Swipe 60-70% of screen dimension, keeping ~30% overlap for orientation. See Workflow §4 for the formula. |
| Scroll a picker or small widget (single step) | `node_action` `scroll_forward`/`scroll_backward` |
| Tap/swipe by coordinates | `shell` if no node index exists |
| Work when accessibility is unavailable | `shell` |
| Interact with a non-native app | neither works — report and stop |
| Find a specific UI element by text in a large tree | `search_nodes` first, then `node_action` with the returned index |

## Important Notes

- **Non-native apps are a dead end.** If `screen_content` returns an error about no accessibility nodes, the app uses Flutter/Unity/WebView/game engine. Do not retry — report to the user.
- **Indices are ephemeral.** Every `screen_content` call produces a fresh tree with new indices. Never pass an index from a previous read.
- **Service auto-setup.** The first tool call automatically enables the accessibility service via root. This takes up to 5 seconds. If root is unavailable, the tool returns an error.
- **Prefer indices over coordinates.** When the tree has a matching node, use `node_action` with its index. Fall back to `gesture` with coordinates only for unlabeled targets.
- **Search before scanning.** When you have a specific target label or text, use `search_nodes` to get candidate indices instead of visually scanning the full `screen_content` YAML tree. It's faster and less error-prone.
- **Coordinates are screen pixels.** Not normalized. Screen dimensions are in the `screen_content` header.
- **No screenshots.** You see only the accessibility tree, not a visual image. If the tree lacks enough context, describe what you can see and ask the user.
- **Prefer gesture for scrolling.** `scroll_forward`/`scroll_backward` step sizes vary per app and container. Use `gesture` with pixel-precise swipes (60-70% of screen dimension) for reliable, consistent list scrolling. Keep ~30% overlap between screens so you can track position. `scroll_forward`/`scroll_backward` are only for single-step increments in pickers or small widgets.
