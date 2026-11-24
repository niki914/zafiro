---
name: threads-playbook
description: Threads operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Threads.
license: MIT
---

# Threads playbook
App package: `com.instagram.barcelona`. Area: short posts and replies.

## What to know
Threads rewards conversational short posts and replies to active accounts. Treat it like a stream of small conversations: ask a real question, add a real detail, reply to every reply.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=4, comment=15, reply=30, like=30, follow=8

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Research and tools for this platform
web_search and web_fetch.

## Screen routines (`screen_routine routine=list`)
`threads.open_home`, `threads.read_activity`, `threads.scan_feed`, `threads.comment`, `threads.like`, `threads.post_text`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/threads.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
