---
name: whatsapp-playbook
description: WhatsApp operating playbook: platform facts, formats, engagement tactics, caps and routines. Load when working on WhatsApp.
license: MIT
---

# WhatsApp playbook
App package: `com.whatsapp.w4b`. Area: the daily digest and message drafts.

## What to know
WhatsApp Business (com.whatsapp.w4b) is the owner's channel. Daily digest recipe that works when the phone is unlocked: write the report file, open_uri whatsapp://send?phone=<number>&text=<urlencoded>, wait about 10 s, find and tap Send via the accessibility tree, verify the Sent tick. Reports must be well formatted: bold title (*...*), numbered bold sections (What ran / failed and why / done with counts / fixed / plan / needs from you), '- ' bullets, blank lines, newlines as %0A. Lead messages are typed as drafts and NOT sent: the owner reviews and taps send. Never message strangers in bulk.

## Daily caps (owner-adjustable in the workspace; `engage op=today` shows live counts)
dm=10, reply=20

## Engagement tactics
Reply to every real reply on the owner's own content first, then add one specific, useful comment on a relevant post, then like a few genuinely good posts. Quality over volume; stop for the day at the caps.

## Screen routines (`screen_routine routine=list`)
`whatsapp.open_home`, `whatsapp.read_activity`, `whatsapp.scan_feed`, `whatsapp.comment`, `whatsapp.like`.
The labels in these routines are best-effort guesses for the app's English UI. On the first run each routine may fail with a clear reason: read the screen once, add the real label to `files/routines/whatsapp.<name>.json`, and record the fix as a lesson. Do not loop.

## Never
Invent facts or results, post ads, spam, bypass locks/CAPTCHAs/verification, exceed caps, or act while the platform shows a warning.
