---
name: prompt-engineering
description: Guidelines to writing prompts, docs, or tasks for AI to read or execute, with strict context isolation.
trigger: write a prompt|improve wiki|update documentation|dispatching agents|
---

# prompt-engineer

Translate user commands into precise prompts or documentation edits. Eliminate context pollution ruthlessly.

<prompt_engineering_standards>
- AX First: System architecture must be designed for AI consumption.
- Clear Delimiters: Use explicit XML tags to isolate context, delimit instructions, and prevent injection. Skip XML wrappers when the content already has its own unambiguous boundary (e.g., a markdown heading, a code fence) — adding XML on top adds ceremony without improving isolation.
- CoT & Verification: Perform internal step-by-step reasoning before outputting. Implement reverse-verification (e.g., "What specific error occurs if this info is missing?").
- Mandatory Pre-flight Checklist: BEFORE outputting a file diff, executing a tool, or delegating a task, internally verify: 1. Is the payload/memory written strictly in English? 2. Are credentials (Token/PAT) completely scrubbed? Halt execution if validation fails.
- Few-shot Examples: Provide 3-5 examples including Negative Examples to establish clear boundaries.
- Zero Silent Drops: Log drops/errors explicitly. No backpressure blackholes.
- Semantic Precision: Eliminate abstract adjectives; use executable metrics.
</prompt_engineering_standards>

<stateless_document_modification>
To prevent "version baggage" and "context pollution" when modifying documentation:
- Treat every update as a clean-slate rebuild. Replace old facts with new facts directly. Never explain history, deltas, or reasoning in the text.
- Delete obsolete content entirely — no ghost context, no "(Updated)" labels. No version tags or timestamps in headers unless explicitly requested.

### Examples

Bad (meta-tagging noise):
```diff
- # Auth Module
+ # Auth Module (v2 Updated)
```

Good: no title change needed.

Bad (conversational baggage):
```diff
- Auth validates tokens.
+ Auth validates tokens and sessions. (Note: it no longer only validates tokens.)
```

Good:
```diff
- Auth validates tokens.
+ Auth validates tokens and sessions.
```
</stateless_document_modification>

<agent_dispatch_rules>
- Task content for subagents MUST be in English and strictly adhere to `<prompt_engineering_standards>`. Preserve native hardcoded strings from the source code.
- Output in the same language the user used in the request.
- Always pass explicit contextual constraints. Never override user requirements with generic implementations.
</agent_dispatch_rules>
