---
name: youtube-playbook
description: YouTube operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on YouTube.
license: MIT
---

# YouTube playbook
App package: `com.google.android.youtube`. Area: shorts, videos and comment replies.

## What to know
YouTube growth depends on titles, thumbnails and retention. The team plans topics, writes scripts, titles, descriptions, chapters and thumbnail briefs, and replies to comments on the owner's videos. Uploading uses YouTube Studio (com.google.android.apps.youtube.creator); the final publish stays with the owner until a verified routine exists.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=1, comment=10, reply=30, like=20

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`youtube.open_home`, `youtube.read_activity`, `youtube.scan_feed`, `youtube.comment`, `youtube.like`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/youtube.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
