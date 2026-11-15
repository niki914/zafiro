---
name: reddit-playbook
description: Reddit operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Reddit.
license: MIT
---

# Reddit playbook
App package: `com.reddit.frontpage`. Area: comments and posts in relevant subreddits.

## What to know
Reddit punishes self-promotion. Contribute first (answers, insight), follow each subreddit's rules (read them before posting), keep promotion under 10%, never post the same text in several subreddits, and expect new or low-karma accounts to be filtered. Reddit has no web fallback from the phone (reddit.com JSON/RSS return 403), so rounds drive the app.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
post=1, comment=8, reply=15, like=20

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`reddit.open_home`, `reddit.read_activity`, `reddit.scan_feed`, `reddit.comment`, `reddit.like`, `reddit.post_text`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/reddit.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
