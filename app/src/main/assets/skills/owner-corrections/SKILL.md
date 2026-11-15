---
name: owner-corrections
description: How to apply a correction from the owner everywhere in one step so the same mistake does not repeat.
license: MIT
---

# Owner corrections
When the owner says something was wrong, bad, off-brand or "do X instead", do all of these in the same turn:
1. `examples op=correct platform=<p> text=<the bad output> reason=<their words>`.
2. Add a one-line standing rule to the workspace rulebook file with `save_file` (path `<workspace>/RULES.md`, keep the old rules, add the new line at the top).
3. Save a lesson with the `memory` tool (specific and reusable).
4. If a skill or agent instruction caused it, say which one so the owner can approve a change.
Reply with what you changed in each place. Do not just apologise.
