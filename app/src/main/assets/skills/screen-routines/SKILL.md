---
name: screen-routines
description: How to drive the phone screen reliably with screen_routine and verified taps instead of step-by-step tapping.
license: MIT
---

# Screen routines
Prefer `screen_routine` over many `screen_operation` calls: `routine=list` shows what exists; `routine=<id> params={...}` does a whole job in one call with every tap verified (the screen must change; one retry; then a clear failure).
After every call read `on_screen_now`: success is what is on screen, not `ok`. If a routine fails: do NOT repeat it. Read the screen once with `screen_operation read`, then (a) fix the wrong label in the routine file `files/routines/<id>.json` (labels are plain text lists; add the real label), or (b) finish the job with `steps=[...]`, or (c) report FAILED with the screen text. The screen lags: when something should have appeared, use `wait` ops rather than tapping again.
The phone must be awake and unlocked. If the screen is black or shows a lock screen, wake it once (`screen_operation key code=224`), and if it stays locked report NEEDS_HUMAN.
