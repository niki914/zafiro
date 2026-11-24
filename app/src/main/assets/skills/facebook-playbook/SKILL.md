---
name: facebook-playbook
description: Facebook operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Facebook.
license: MIT
---

# Facebook playbook
App package: `com.facebook.katana`. Area: page posts, group posts and comments.

## What to know
Facebook rewards native posts, video, and active groups. Group posting must follow each group's rules and admin approval. Never spam groups; one genuinely helpful comment beats a drop of links. Page management may be in the separate Pages Manager app (com.facebook.pages.app).

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=2, comment=10, reply=25, like=25, dm=3

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Research and tools for this platform
Research: group rules via the app; Apify 'apify/facebook-posts-scraper' (if on) for page benchmarks.

## Screen routines (`screen_routine routine=list`)
`facebook.open_home`, `facebook.read_activity`, `facebook.scan_feed`, `facebook.comment`, `facebook.like`, `facebook.post_text`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/facebook.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
