---
name: linkedin-playbook
description: LinkedIn operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on LinkedIn.
license: MIT
---

# LinkedIn playbook
App package: `com.linkedin.android`. Area: posts, comments and connection messages.

## What to know
LinkedIn rewards useful comments on other people's posts and consistent posting with a clear point of view. LinkedIn's terms prohibit third-party automation tools; this workspace only drives the official app on the owner's phone at human pace with low caps. Connection requests and DMs are always drafts for the owner to approve. No fake claims about experience.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=1, comment=12, reply=25, like=25, follow=5, dm=5

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`linkedin.open_home`, `linkedin.read_activity`, `linkedin.scan_feed`, `linkedin.comment`, `linkedin.like`, `linkedin.post_text`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/linkedin.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
