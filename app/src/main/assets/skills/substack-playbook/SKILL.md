---
name: substack-playbook
description: Substack operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Substack.
license: MIT
---

# Substack playbook
App package: `com.substack.app`. Area: Notes and posts.

## What to know
Substack has two surfaces: Notes (short posts in a feed, replies, restacks, likes) and long posts (newsletter, comments). Audience growth on Substack comes mostly from Notes and from thoughtful comments on other writers' posts and notes, plus recommendations from other publications. Notes that are only links tend to travel less. A restack shares someone's note or post with your followers (add your own thought when you can). The Activity tab shows replies, likes, new subscribers and restacks. The owner's publication: tricreta.substack.com (Android developer; websites, AI features, dashboards, design/video). Content pillars come from the owner's content plan; WhatsApp/M-Pesa/bot topics are at most 1 in 6 pieces.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
note=6, post=1, comment=15, reply=40, like=30, restack=10, follow=10, dm=5

## Engagement tactics
Notes first: 3-6 Notes a day max. Comments on larger writers' posts in the niche are the best discovery lever: add one specific insight, no pitch. Reply to every comment on the owner's posts. Restack a few good notes with a line of your own.

## Research and tools for this platform
Research: rss_read on https://<writer>.substack.com/feed for each writer in your niche; web_search for niche topics; Notes and comments via screen routines.

## Screen routines (`screen_routine routine=list`)
`substack.open_home`, `substack.read_activity`, `substack.scan_feed`, `substack.comment`, `substack.like`, `substack.post_text`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/substack.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
