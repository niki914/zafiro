---
name: pinterest-playbook
description: Pinterest operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Pinterest.
license: MIT
---

# Pinterest playbook
App package: `com.pinterest`. Area: pins and boards.

## What to know
Pinterest is a search engine: pin titles, descriptions and board names carry keywords; fresh pins to the same URL beat repeats. The team prepares pin images (files), titles, descriptions and target boards; publishing is the owner's tap until a routine is verified.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=5, like=20, follow=10

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Research and tools for this platform
Research: pinterest search suggestions via the app; make_card for pin images in 1000x1500.

## Screen routines (`screen_routine routine=list`)
`pinterest.open_home`, `pinterest.read_activity`, `pinterest.scan_feed`, `pinterest.comment`, `pinterest.like`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/pinterest.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
