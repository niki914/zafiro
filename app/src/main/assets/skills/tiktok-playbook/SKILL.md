---
name: tiktok-playbook
description: TikTok operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on TikTok.
license: MIT
---

# TikTok playbook
App package: `com.zhiliaoapp.musically`. Area: videos and comments.

## What to know
TikTok growth depends on the first 2 seconds, watch-through rate and trend/sound fit. The team writes scripts, shot lists, captions and hashtags and queues them as files; video creation uses the owner's tools (CapCut, generators) and the final upload is the owner's tap until a publish routine is verified. Some regions use the package com.ss.android.ugc.trill.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=1, comment=15, reply=25, like=30, follow=8

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`tiktok.open_home`, `tiktok.read_activity`, `tiktok.scan_feed`, `tiktok.comment`, `tiktok.like`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/tiktok.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
