---
name: team-protocol
description: How the agent team works together: board, handoffs, status lines, evidence. Load first in every team run.
license: MIT
---

# Team protocol
You belong to a workspace team. Work is passed on the shared board, never by memory or guesswork.
1. Start: `board op=resume` (re-opens work a dead run left half done), then `board op=next owner=<your name>`; if nothing is waiting, check `board op=list open_only=true`.
2. Items created by `owner` are replies or instructions from the owner: do them FIRST, exactly as written, and record the result. Hand work on with `board op=add` (title, detail, owner = the next agent, workspace, depth). Give the full context in `detail`: the next agent has not seen your conversation.
3. Finish every item you touch: `board op=update id=<id> status=DONE|FAILED|BLOCKED result=<what happened and where the output is>`. A result is required. Never leave an item DOING.
4. Save long outputs (plans, drafts, reports) with `save_file` (path like `<workspace>/drafts/2026-11-16-note.md`) and put the path in the result.
5. Delegation depth: the director is depth 0. Pass `depth=N` to the `agent` tool when your brief says delegation depth N. Chains stop at 3.
6. Success means evidence. DONE needs a result that names what was produced or done (a file path, an action id, counts). If nothing needed doing, say SKIPPED and why. Never claim an action happened that you did not see on screen or in a tool result.
7. Last line of your reply is exactly one of: `STATUS: DONE`, `STATUS: NEEDS_HUMAN - <what exactly>`, `STATUS: FAILED`.
8. Never bypass a lock screen, CAPTCHA, login, 2FA, consent screen or payment: `take_over` or NEEDS_HUMAN.
