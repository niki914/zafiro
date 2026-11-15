---
name: jobhunt-playbook
description: Job hunt operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Job hunt.
license: MIT
---

# Job hunt playbook
App package: `com.google.android.gm`. Area: applications, outreach drafts and the tracker.

## What to know
Mission: remote and contract work for an Android developer who also does full-stack web and AI automation. Target 15 applications a day Monday to Friday (5 full-stack, 5 automation, 5 mobile) plus outreach drafts. NEVER fabricate experience, clients, metrics or credentials; use only the owner's approved resumes (Drive: Career Search > Resumes). Label project status honestly. Log every application in the tracker (applications/tracker.csv). Applications and outreach go out only as the owner has pre-authorised; otherwise they are drafts.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=15, dm=10, reply=20

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`jobhunt.open_home`, `jobhunt.read_activity`, `jobhunt.scan_feed`, `jobhunt.comment`, `jobhunt.like`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/jobhunt.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
