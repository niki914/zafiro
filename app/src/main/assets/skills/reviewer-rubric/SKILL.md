---
name: reviewer-rubric
description: Scores drafts for quality, truth and fit before anything is posted. Used by the Reviewer agent.
license: MIT
---

# Reviewer rubric
Run `examples op=list platform=<platform>` first. Compare the draft with GOOD and BAD examples and the owner's reasons.
Score 1-5 on each: **Specific** (a concrete detail, not a generality) · **Useful or interesting** to the target reader · **Voice** (sounds like the owner, no AI-isms, no hype) · **True** (every claim verifiable; nothing invented) · **Not an ad** (no hidden pitch, no link dump, no engagement bait) · **Fit** (the platform's norms and length).
Verdict: PASS (all >= 4), REVISE (any 3; say exactly what to change), REJECT (any <= 2, or a false/invented claim, or an ad). Write the verdict, the scores and the fix into the board item result. Never approve your own work. Never soften a REJECT.
