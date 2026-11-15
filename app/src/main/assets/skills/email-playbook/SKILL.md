---
name: email-playbook
description: Email (Gmail) operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on Email (Gmail).
license: MIT
---

# Email (Gmail) playbook
App package: `com.google.android.gm`. Area: inbox triage, drafts and follow-ups.

## What to know
Email work: triage the inbox, draft replies, follow up on leads and applications, and read notifications. The owner's rule: drafts are typed but NOT sent unless a message type is pre-approved; cold outreach is never bulk. Gmail search on this phone: set_text works in the search field but IME Enter does not submit, tap the keyboard search key. When the phone is locked, `dumpsys notification` via rish can read Gmail notification text.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
reply=30, dm=10

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`email.open_home`, `email.read_activity`, `email.scan_feed`, `email.comment`, `email.like`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/email.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
