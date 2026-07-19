---
name: Phone Use
description: Control the Android device through accessibility-based UI interactions. Use for tasks that require reading app screens, locating UI nodes, and simulating touch, swipe, or key input on device. Prefer purpose-built tools or APIs when available.
---

## Overview

Phone Use gives you the ability to see and interact with the Android device screen. It uses Android AccessibilityService (with root-assisted auto-setup) to read the UI tree and simulate user actions.

Four core tools:

| Tool | Purpose |
|:-----|:--------|
| `screen_content` | Read the current screen's accessibility tree as YAML |
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

### 3. Act on the tree

Use `node_action` with the node's index:

```
node_action(action: "click", index: 42)
node_action(action: "set_text", index: 7, text: "hello")
node_action(action: "scroll_forward", index: 15)
```

For actions that don't target a single labeled node (e.g., swipe-to-refresh, drag items), use `gesture` with coordinates.

### 4. Re-read after every action

**Always call `screen_content` again after each action before deciding the next step.** Indices are freshly assigned on every `screen_content` call and stale immediately. Never reuse an index from a previous screen read.

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
| `txt` | Display text (quoted if it contains special characters) |
| `h` | Content description / accessibility identifier |
| `tap` | Node is clickable (only shown when true) |
| `hold` | Node is long-clickable (only shown when true) |
| `edit` | Node is editable text input (only shown when true) |
| `scroll` | Node is scrollable (only shown when true) |
| `checked` | Node has checked state — switches, checkboxes (only shown when true) |
| `ch` | Child nodes |
| `more` | Count of off-screen children truncated from output |

Boolean attributes (`tap`, `hold`, `edit`, `scroll`, `checked`) are only emitted when true. If absent, the attribute is false.

## Method Selection

| If you need to... | Use |
|:------------------|:----|
| Type text into a field | `accessibility` (only option) |
| Tap a labeled UI element | `accessibility` (default, auto-fallbacks) |
| Scroll a list | `accessibility` first |
| Tap/swipe by coordinates | `shell` if no node index exists |
| Work when accessibility is unavailable | `shell` |
| Interact with a non-native app | neither works — report and stop |

## Important Notes

- **Non-native apps are a dead end.** If `screen_content` returns an error about no accessibility nodes, the app uses Flutter/Unity/WebView/game engine. Do not retry — report to the user.
- **Indices are ephemeral.** Every `screen_content` call produces a fresh tree with new indices. Never pass an index from a previous read.
- **Service auto-setup.** The first tool call automatically enables the accessibility service via root. This takes up to 5 seconds. If root is unavailable, the tool returns an error.
- **Prefer indices over coordinates.** When the tree has a matching node, use `node_action` with its index. Fall back to `gesture` with coordinates only for unlabeled targets.
- **Coordinates are screen pixels.** Not normalized. Screen dimensions are in the `screen_content` header.
- **No screenshots.** You see only the accessibility tree, not a visual image. If the tree lacks enough context, describe what you can see and ask the user.
- **Scroll actions use the node's center.** `scroll_forward` and `scroll_backward` target the node at the given index, not arbitrary coordinates.
