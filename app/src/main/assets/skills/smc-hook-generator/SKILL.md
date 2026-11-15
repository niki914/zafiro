---
name: smc-hook-generator
description: >
  Generate 6 clickbait-style LinkedIn hook variations for any topic. Two-line hooks built on the formula: a 40-char opening line, a 40-char bold contrast line. Uses supplied numbers and genuine first-person experience when available. Use this skill whenever the user says "write me hooks", "hook ideas", "generate hooks", "I need a hook for a post about...", or pastes a topic and asks for openers. Fast output, no preamble.
---

# Hook Generator

## Codex and Claude runtime

- Use this skill in Codex or Claude with the tools actually available in the current task. `AskUserQuestion` examples describe the questions, not a required API: use an available question tool within its limits, or ask in chat. Reuse answers and source material already supplied.
- Work in the user-selected project. Read its `about-me.md`, `voice.md` and relevant brand files before personalised work. Confirm the intended author if files conflict or contain starter defaults. Ask for missing facts or run `voice-builder`; never inherit the maintainer's identity, accounts or private files.
- Resolve bundled `references/` relative to this skill folder. For an explicitly requested profile refresh, read and update the canonical `about-me.md`, `voice.md` or `newsletter-voice.md` in place, preserving unrelated user facts and rules. Consumers must reread those canonical files. Use a new filename only for new deliverables that would collide with unrelated existing files. Installation alone never starts an interview or writes files. Do not write persistent learnings unless requested.
- Use supplied evidence first. Verify external claims through available search/source tools when needed. If a source or integration is unavailable, name the missing capability and offer supplied text/export input. Never invent facts, first-person experience, metrics or a successful tool run.
- Connect only services needed for the chosen route through the user's existing account. Never print credentials or overwrite connections. Drafting, saving and reviewing do not authorise publishing, sending messages or changing accounts.

## CRITICAL: Auto-start on load

When this skill triggers, go straight to Step 1. Do not summarise. Do not explain what makes a good hook.

## Step 1. Get the topic

If the user already pasted a topic in their message, use it and skip to Step 2.

Otherwise ask:

> What topic do you want hooks for?

Wait for response.

## Step 2. Write 6 hook variations

Every hook has the same structure:

- **Line 1 (Opening)**: 40 characters maximum. No questions. States something unexpected, specific, or punchy.
- **Line 2 (Contrast)**: 40 characters maximum. Contradicts, reframes, or undercuts the opening.

Every variation must:

- Use "How I" or "I" only for a real experience supplied by the author. Otherwise use an evidence-backed nonpersonal angle and label the unavailable personal angle.
- Include a digit or metric where possible
- Follow clickbait principles: tension, curiosity gap, stakes

Produce 6 variations covering different angles:

1. **Number-led**: Lead with a specific number or metric
2. **Contrarian**: State a belief then flip it
3. **Personal transformation**: Before vs after with a digit
4. **Authority reference**: Reference a name, tool, or brand
5. **Admission**: Confess a mistake or loss
6. **Future shock**: A prediction or "X is about to change"

## Step 3. Output format

```
HOOKS for [topic]

1. [Number-led]
[Line 1]
[Line 2]

2. [Contrarian]
[Line 1]
[Line 2]

3. [Personal transformation]
[Line 1]
[Line 2]

4. [Authority reference]
[Line 1]
[Line 2]

5. [Admission]
[Line 1]
[Line 2]

6. [Future shock]
[Line 1]
[Line 2]
```

## Step 4. Offer the next move

Ask:

> Want me to build one of these into a full post? Call the post-formatter skill with the hook number.

## Rules

- 40 characters maximum per line. Count them.
- No questions in the opening line.
- No em dashes.
- No filler words. Every word earns its place.
- Prefer digits over spelled numbers (3, not three).
- British English unless voice.md says otherwise.
- Preserve uncertainty in the source. Never invent a result or certainty to make a hook stronger. Count the exact characters with an available text tool before delivery.
